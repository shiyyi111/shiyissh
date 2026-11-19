package com.shiyi.infrastructure.adapter.embedding;

import com.shiyi.domain.agent.adapter.embedding.EmbeddingClient;
import com.shiyi.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.shiyi.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import com.shiyi.domain.agent.model.valobj.properties.RagProperties;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 走 OpenAI 兼容协议的向量化客户端（当前指向 DashScope 的 {@code /embeddings}）。
 * <p>
 * 设计要点：
 * <ol>
 *   <li><b>失败不抛异常</b>：向量化是"锦上添花"的能力，通道挂了应该降级成纯关键词检索，
 *       而不是让用户的问题直接失败。所以这里所有异常都吞掉、返回空列表/null，由上层决定降级；</li>
 *   <li><b>可关闭</b>：{@code app.rag.embedding.enabled=false} 时 {@link #available()} 直接返回 false，
 *       连客户端都不会创建；</li>
 *   <li><b>配置优先</b>：{@code app.rag.embedding.*} 里配了 api-key/base-url 就用它，
 *       没配则回退到系统属性与环境变量（与 chat 模型同一套 key）。</li>
 * </ol>
 *
 * @author shiyi
 */
@Service
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingClient.class);

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEFAULT_EMBEDDINGS_PATH = "/embeddings";
    private static final String DEFAULT_MODEL = "text-embedding-v4";
    private static final int DEFAULT_DIMENSIONS = 1024;
    private static final int DEFAULT_BATCH_SIZE = 16;

    @Resource
    private RagProperties ragProperties;

    /**
     * agent 配置里的模型接入信息。向量化与 chat 用的是同一个网关与同一把 key，
     * 所以这里复用它，避免 yml 里再抄一遍 api-key（抄了就会漂移）。
     * 单测 / 未装配时为 null，构造流程会回退到系统属性与环境变量。
     */
    @Autowired(required = false)
    private AiAgentAutoConfigProperties agentProperties;

    /** 懒创建的客户端；创建失败后置为 null，下次调用会再试（配置可能被改）。 */
    private volatile OpenAiApi api;
    private volatile boolean initialized;

    @Override
    public boolean available() {
        if (!embeddingEnabled()) {
            return false;
        }
        return resolveApi() != null;
    }

    @Override
    public int dimensions() {
        RagProperties.Embedding embedding = embedding();
        return embedding != null && embedding.getDimensions() > 0 ? embedding.getDimensions() : DEFAULT_DIMENSIONS;
    }

    @Override
    public String model() {
        RagProperties.Embedding embedding = embedding();
        return embedding != null && notBlank(embedding.getModel()) ? embedding.getModel() : DEFAULT_MODEL;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        List<float[]> vectors = embedBatch(List.of(text));
        return vectors.isEmpty() ? null : vectors.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        OpenAiApi client = resolveApi();
        if (client == null) {
            return List.of();
        }
        List<String> prepared = new ArrayList<>(texts.size());
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                prepared.add("");
                continue;
            }
            int maxChars = maxChars();
            prepared.add(text.length() > maxChars ? text.substring(0, maxChars) : text);
        }
        // 网关对单次请求条数有上限（DashScope 该模型为 10），超限会 400 且整批失败。
        // 这里按 batch-size 分片；任一片失败都视为整批失败，由上层统一降级。
        int batch = batchSize();
        List<float[]> vectors = new ArrayList<>(prepared.size());
        for (int from = 0; from < prepared.size(); from += batch) {
            int to = Math.min(prepared.size(), from + batch);
            List<float[]> part = embedChunk(client, prepared.subList(from, to));
            if (part == null || part.size() != to - from) {
                return List.of();
            }
            vectors.addAll(part);
        }
        return vectors;
    }

    /**
     * 单次向量化请求，条数不超过 {@code batch-size}。
     *
     * @return 与入参等长的向量列表；失败返回 {@code null}，由 {@link #embedBatch} 统一降级
     */
    private List<float[]> embedChunk(OpenAiApi client, List<String> chunk) {
        try {
            ResponseEntity<OpenAiApi.EmbeddingList<OpenAiApi.Embedding>> response = client.embeddings(
                    new OpenAiApi.EmbeddingRequest<>(chunk, model(), "float", dimensions(), null));
            OpenAiApi.EmbeddingList<OpenAiApi.Embedding> body = response == null ? null : response.getBody();
            if (body == null || body.data() == null) {
                return null;
            }
            List<float[]> vectors = new ArrayList<>(body.data().size());
            for (OpenAiApi.Embedding embedding : body.data()) {
                // 网关按输入顺序返回，这里保持同样顺序：调用方按 index 对回原文
                vectors.add(embedding.embedding());
            }
            return vectors;
        } catch (Exception e) {
            log.warn("向量化请求失败，将降级为纯关键词检索：{}", e.getMessage());
            return null;
        }
    }

    private int batchSize() {
        RagProperties.Embedding embedding = embedding();
        int size = embedding == null ? 0 : embedding.getBatchSize();
        return size > 0 ? size : DEFAULT_BATCH_SIZE;
    }

    /**
     * 创建/复用客户端。注意 base-url 已经包含 {@code /v1}，所以只拼 {@code /embeddings}。
     */
    private OpenAiApi resolveApi() {
        if (initialized && api != null) {
            return api;
        }
        synchronized (this) {
            if (initialized && api != null) {
                return api;
            }
            AgentApi agent = agentApi();
            String apiKey = firstNotBlank(embeddingApiKey(),
                    agent == null ? null : agent.apiKey(),
                    System.getProperty("llm.api-key"),
                    System.getenv("DASHSCOPE_API_KEY"),
                    System.getenv("LLM_API_KEY"));
            if (apiKey == null) {
                if (!initialized) {
                    log.warn("未找到向量化 api-key（app.rag.embedding.api-key / ai.agent.config 里的 ai-api.api-key / "
                            + "llm.api-key / DASHSCOPE_API_KEY 均为空），知识库将只用关键词检索");
                }
                initialized = true;
                api = null;
                return null;
            }
            String baseUrl = firstNotBlank(embeddingBaseUrl(),
                    System.getProperty("llm.base-url"),
                    System.getenv("LLM_BASE_URL"),
                    agent == null ? null : agent.baseUrl(),
                    DEFAULT_BASE_URL);
            // 路径跟 base-url 走：自己配了 base-url 时它一般已含 /v1，用 /embeddings；
            // 复用 agent 的 base-url（如 https://dashscope.aliyuncs.com/compatible-mode）时用 /v1/embeddings。
            String embeddingsPath;
            if (notBlank(embeddingBaseUrl())) {
                embeddingsPath = firstNotBlank(embeddingEmbeddingsPath(), DEFAULT_EMBEDDINGS_PATH);
            } else if (agent != null && notBlank(agent.embeddingsPath())) {
                embeddingsPath = agent.embeddingsPath();
            } else {
                embeddingsPath = firstNotBlank(embeddingEmbeddingsPath(), DEFAULT_EMBEDDINGS_PATH);
            }
            try {
                api = OpenAiApi.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .embeddingsPath(embeddingsPath)
                        .build();
                log.info("向量化客户端装配完成：baseUrl={} path={} model={} dimensions={}",
                        baseUrl, embeddingsPath, model(), dimensions());
            } catch (Exception e) {
                log.warn("向量化客户端装配失败：{}", e.getMessage());
                api = null;
            }
            initialized = true;
            return api;
        }
    }

    /**
     * 取 agent 配置里第一个可用表的模型接入信息。
     * <p>
     * 只读、不建连接：这是"要不要复用 chat 的 key"的判断依据，不是路由配置。
     */
    private AgentApi agentApi() {
        if (agentProperties == null || agentProperties.getTables() == null) {
            return null;
        }
        for (AiAgentConfigTableVO table : agentProperties.getTables().values()) {
            if (table == null || table.getModule() == null || table.getModule().getAiApi() == null) {
                continue;
            }
            AiAgentConfigTableVO.Module.AiApi value = table.getModule().getAiApi();
            if (notBlank(value.getApiKey())) {
                return new AgentApi(value.getApiKey(), value.getBaseUrl(), value.getEmbeddingsPath());
            }
        }
        return null;
    }

    private boolean embeddingEnabled() {
        RagProperties.Embedding embedding = embedding();
        return ragProperties == null || embedding == null || embedding.isEnabled();
    }

    private RagProperties.Embedding embedding() {
        return ragProperties == null ? null : ragProperties.getEmbedding();
    }

    private String embeddingApiKey() {
        RagProperties.Embedding embedding = embedding();
        return embedding == null ? null : embedding.getApiKey();
    }

    private String embeddingBaseUrl() {
        RagProperties.Embedding embedding = embedding();
        return embedding == null ? null : embedding.getBaseUrl();
    }

    private String embeddingEmbeddingsPath() {
        RagProperties.Embedding embedding = embedding();
        return embedding == null ? null : embedding.getEmbeddingsPath();
    }

    private int maxChars() {
        RagProperties.Embedding embedding = embedding();
        return embedding != null && embedding.getMaxChars() > 0 ? embedding.getMaxChars() : 2000;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstNotBlank(String... values) {
        for (String value : values) {
            if (notBlank(value)) {
                return value;
            }
        }
        return null;
    }

    /** agent 配置里与向量化相关的一小段信息，避免方法参数散落三处。 */
    private record AgentApi(String apiKey, String baseUrl, String embeddingsPath) {
    }
}