package com.shiyi.infrastructure.adapter.rerank;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.shiyi.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.shiyi.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import com.shiyi.domain.agent.model.valobj.properties.RagProperties;
import com.shiyi.domain.agent.service.knowledge.KnowledgeRetriever;
import com.shiyi.domain.agent.service.knowledge.RerankService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * DashScope 重排客户端（默认 {@code qwen3.7-text-rerank}）。
 * <p>
 * 为什么走原生端点而不是 OpenAI 兼容端点：DashScope 的 rerank 只有
 * {@code /api/v1/services/rerank/text-rerank/text-rerank} 这一套形状，
 * 兼容模式里没有对应的 OpenAI 风格接口。请求/响应都很简单，手写 HTTP 比引 SDK 便宜。
 * <p>
 * 三条硬约束（对应 {@link RerankService} 的契约）：
 * <ol>
 *   <li><b>不抛异常</b>：没配 key、超时、限流、返回体不符预期，一律返回原顺序。
 *       重排是排序优化，不能让它把一次正常的问答变成 500；</li>
 *   <li><b>不返回空</b>：失败时必须把入参原样交回去。返回空列表会被上层当成"知识库没有相关内容"，
 *       把"重排失败"伪装成"没有命中"，是最难排查的一类故障；</li>
 *   <li><b>有界</b>：最多送 {@code max-docs} 条、每条截 {@code max-chars} 字、超时 {@code timeout-ms}。</li>
 * </ol>
 *
 * @author shiyi
 */
@Service
public class DashScopeRerankClient implements RerankService {

    private static final Logger log = LoggerFactory.getLogger(DashScopeRerankClient.class);

    private static final String DEFAULT_URL = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    @Resource
    private RagProperties ragProperties;

    /** 与 embedding 同一套复用逻辑：没单独配 key 时借用 chat 模型的 api-key。 */
    @Autowired(required = false)
    private AiAgentAutoConfigProperties agentProperties;

    private volatile HttpClient httpClient;

    private volatile boolean warned;

    @Override
    public boolean available() {
        RagProperties.Rerank rerank = rerank();
        if (rerank == null || !rerank.isEnabled()) {
            return false;
        }
        String key = apiKey();
        if (key == null) {
            if (!warned) {
                warned = true;
                log.warn("未找到 rerank api-key（app.rag.rerank.api-key / app.rag.embedding.api-key / "
                        + "ai.agent.config 的 ai-api.api-key / llm.api-key / DASHSCOPE_API_KEY 均为空），"
                        + "知识检索将跳过重排，只用召回分数排序");
            }
            return false;
        }
        return true;
    }

    @Override
    public List<KnowledgeRetriever.Hit> rerank(String query, List<KnowledgeRetriever.Hit> hits, int topN) {
        if (hits == null || hits.size() <= 1 || query == null || query.isBlank()) {
            return hits;
        }
        RagProperties.Rerank rerank = rerank();
        if (rerank == null || !rerank.isEnabled()) {
            return hits;
        }
        String key = apiKey();
        if (key == null) {
            return hits;
        }
        int maxDocs = Math.max(2, rerank.getMaxDocs());
        int limit = Math.min(maxDocs, hits.size());
        List<KnowledgeRetriever.Hit> candidates = hits.subList(0, limit);
        try {
            List<Double> scores = requestScores(query, candidates, rerank, key);
            if (scores == null || scores.size() != candidates.size()) {
                return hits;
            }
            // 按重排分降序；分数相同时保持原序（稳定排序，避免同分条目来回跳）
            List<Integer> order = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                order.add(i);
            }
            order.sort(Comparator.<Integer>comparingDouble(i -> scores.get(i)).reversed());
            List<KnowledgeRetriever.Hit> merged = new ArrayList<>(hits.size());
            for (Integer i : order) {
                merged.add(candidates.get(i));
            }
            // 超过 maxDocs 的尾部保持原相对顺序，不因"没送进模型"而被丢掉
            if (hits.size() > limit) {
                merged.addAll(hits.subList(limit, hits.size()));
            }
            int keep = topN > 0 ? Math.min(topN, merged.size()) : merged.size();
            return new ArrayList<>(merged.subList(0, keep));
        } catch (Exception e) {
            log.warn("rerank 失败，保留召回顺序：{}", e.getMessage());
            return hits;
        }
    }

    /**
     * 调一次 rerank，返回与 {@code candidates} 等长的分数表。
     * 解析不出等长结果时返回 null（调用方回退原序），不猜。
     */
    private List<Double> requestScores(String query, List<KnowledgeRetriever.Hit> candidates,
                                       RagProperties.Rerank rerank, String key) throws Exception {
        JSONArray documents = new JSONArray(candidates.size());
        int maxChars = Math.max(100, rerank.getMaxChars());
        for (KnowledgeRetriever.Hit hit : candidates) {
            documents.add(documentText(hit, maxChars));
        }
        JSONObject parameters = new JSONObject();
        parameters.put("top_n", candidates.size());
        // 只要分数，不要回传正文：正文我们本地就有，回传只会把响应体放大一倍
        parameters.put("return_documents", false);
        JSONObject input = new JSONObject();
        input.put("query", query);
        input.put("documents", documents);
        JSONObject body = new JSONObject();
        body.put("model", model());
        body.put("input", input);
        body.put("parameters", parameters);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url()))
                .timeout(Duration.ofMillis(Math.max(100, rerank.getTimeoutMs())))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " " + brief(response.body()));
        }
        JSONObject json = JSON.parseObject(response.body());
        JSONObject output = json == null ? null : json.getJSONObject("output");
        JSONArray results = output == null ? null : output.getJSONArray("results");
        if (results == null || results.size() != candidates.size()) {
            throw new IllegalStateException("返回结果条数不符：期望 " + candidates.size()
                    + "，实际 " + (results == null ? 0 : results.size()));
        }
        List<Double> scores = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            scores.add(0.0d);
        }
        for (int i = 0; i < results.size(); i++) {
            JSONObject item = results.getJSONObject(i);
            Integer index = item == null ? null : item.getInteger("index");
            Double score = item == null ? null : item.getDouble("relevance_score");
            if (index == null || score == null || index < 0 || index >= candidates.size()) {
                throw new IllegalStateException("返回结果缺少 index/relevance_score");
            }
            scores.set(index, score);
        }
        return scores;
    }

    /** 送进重排的文本 = 标题 + 命中片段；命令与标签对"是否回答了问题"帮助很小，不送。 */
    private String documentText(KnowledgeRetriever.Hit hit, int maxChars) {
        StringBuilder sb = new StringBuilder();
        if (hit.title() != null && !hit.title().isBlank()) {
            sb.append(hit.title()).append('\n');
        }
        if (hit.snippet() != null && !hit.snippet().isBlank()) {
            sb.append(hit.snippet());
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) {
            // 标题片段都空时兜底用 docId，保证 documents 与 candidates 一一对应且非空串
            text = hit.docId() == null ? "-" : hit.docId();
        }
        return text.length() > maxChars ? text.substring(0, maxChars) : text;
    }

    private HttpClient client() {
        HttpClient client = httpClient;
        if (client == null) {
            synchronized (this) {
                client = httpClient;
                if (client == null) {
                    client = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofMillis(Math.max(100, rerank().getTimeoutMs())))
                            .build();
                    httpClient = client;
                }
            }
        }
        return client;
    }

    private RagProperties.Rerank rerank() {
        return ragProperties == null ? null : ragProperties.getRerank();
    }

    private String model() {
        RagProperties.Rerank rerank = rerank();
        return rerank != null && notBlank(rerank.getModel()) ? rerank.getModel() : "qwen3.7-text-rerank";
    }

    private String url() {
        RagProperties.Rerank rerank = rerank();
        return rerank != null && notBlank(rerank.getBaseUrl()) ? rerank.getBaseUrl() : DEFAULT_URL;
    }

    /**
     * key 解析顺序：{@code app.rag.rerank.api-key} → {@code app.rag.embedding.api-key}
     * → agent 配置的 {@code ai-api.api-key} → {@code llm.api-key} → 环境变量。
     * 与 {@code OpenAiEmbeddingClient} 保持一致，避免"向量化能跑、重排没 key"这类半死配置。
     */
    private String apiKey() {
        RagProperties.Rerank rerank = rerank();
        String key = firstNotBlank(rerank == null ? null : rerank.getApiKey(),
                ragProperties == null || ragProperties.getEmbedding() == null
                        ? null : ragProperties.getEmbedding().getApiKey(),
                agentApiKey(),
                System.getProperty("llm.api-key"),
                System.getenv("DASHSCOPE_API_KEY"),
                System.getenv("LLM_API_KEY"));
        return notBlank(key) ? key.trim() : null;
    }

    private String agentApiKey() {
        if (agentProperties == null || agentProperties.getTables() == null) {
            return null;
        }
        for (AiAgentConfigTableVO table : agentProperties.getTables().values()) {
            if (table == null || table.getModule() == null || table.getModule().getAiApi() == null) {
                continue;
            }
            String key = table.getModule().getAiApi().getApiKey();
            if (notBlank(key)) {
                return key;
            }
        }
        return null;
    }

    private static String firstNotBlank(String... values) {
        for (String value : values) {
            if (notBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String brief(String body) {
        if (body == null) {
            return "";
        }
        String text = body.replace('\n', ' ').trim();
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }
}