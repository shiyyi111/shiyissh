package com.shiyi.domain.agent.service.chat;

import com.shiyi.domain.agent.adapter.embedding.EmbeddingClient;
import com.shiyi.domain.agent.adapter.vector.SparseVector;
import com.shiyi.domain.agent.adapter.vector.VectorStore;
import com.shiyi.domain.agent.model.valobj.properties.RagProperties;
import com.shiyi.domain.agent.service.knowledge.impl.SparseEncoder;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话摘要的向量索引与跨会话召回（P4）。
 * <p>
 * 为什么摘要要进向量库、而原文不进：用户下次开新会话问"上次那台机器 502 是怎么解决的"，
 * 需要的是<b>结论</b>而不是逐条消息。摘要正是结论的载体，体积小（≤400 字）、一条一个点。
 * 历史原文留在 {@code chat_message}（MySQL），需要明细时按 session 回表。
 * <p>
 * 几个约束：
 * <ul>
 *   <li><b>写是 best-effort</b>：摘要已经落库成功，向量化失败只丢"跨会话可搜"，
 *       不能反过来让摘要保存失败；</li>
 *   <li><b>读不阻塞对话</b>：召回失败返回空列表，provider 直接不渲染该段；</li>
 *   <li><b>按用户隔离</b>：filter {@code user_id}，且同一用户跨主机也允许召回
 *       （"上次那台机器"本身就是跨主机的记忆需求）；当前会话自身要排除——它的上下文
 *       已经在窗口里了，再放一遍属于重复。</li>
 * </ul>
 *
 * @author shiyi
 */
@Service
public class SummaryVectorService {

    private static final Logger log = LoggerFactory.getLogger(SummaryVectorService.class);

    /** 集合名后缀：{@code <prefix>_summary_hybrid_<dimensions>}。 */
    public static final String COLLECTION_SUFFIX = "_summary_hybrid_";

    /** 点 ID 前缀，避免和知识库/kv 的 id 冲突。 */
    public static final String POINT_PREFIX = "sum:";

    /** 参与向量化的摘要上限（摘要本身 ≤400 字，这里只是防脏数据）。 */
    private static final int MAX_TEXT_CHARS = 2000;

    /** 渲染给模型的单条摘要上限。 */
    public static final int PREVIEW_CHARS = 400;

    @Resource
    private RagProperties ragProperties;

    private final ObjectProvider<EmbeddingClient> embeddingClientProvider;

    private final ObjectProvider<VectorStore> vectorStoreProvider;

    private volatile boolean collectionReady;

    public SummaryVectorService(ObjectProvider<EmbeddingClient> embeddingClientProvider,
                                ObjectProvider<VectorStore> vectorStoreProvider) {
        this.embeddingClientProvider = embeddingClientProvider;
        this.vectorStoreProvider = vectorStoreProvider;
    }

    /**
     * 当前是否具备"摘要可索引/可召回"的条件。
     */
    public boolean available() {
        if (ragProperties == null || ragProperties.getVector() == null
                || !ragProperties.getVector().isEnabled()) {
            return false;
        }
        EmbeddingClient client = embeddingClientProvider.getIfAvailable();
        return client != null && client.available() && vectorStoreProvider.getIfAvailable() != null;
    }

    /** 摘要集合名。 */
    public String collectionName() {
        int dimensions = ragProperties == null || ragProperties.getEmbedding() == null
                ? 1024 : ragProperties.getEmbedding().getDimensions();
        return ragProperties.getVector().getCollectionPrefix() + COLLECTION_SUFFIX + dimensions;
    }

    /**
     * 把一条会话摘要写进向量库（best-effort，不抛异常）。
     * <p>
     * 点 id 用 {@code sum:<sessionId>}：同一会话反复更新摘要时是覆盖写，不会残留旧版本。
     *
     * @param sessionId 会话 ID
     * @param userId    归属用户（召回时按它过滤）
     * @param summary   摘要正文
     */
    public void index(String sessionId, String userId, String summary) {
        if (sessionId == null || sessionId.isBlank() || summary == null || summary.isBlank()) {
            return;
        }
        VectorStore store = vectorStoreProvider.getIfAvailable();
        EmbeddingClient client = embeddingClientProvider.getIfAvailable();
        if (store == null || client == null || !client.available()) {
            return;
        }
        try {
            ensureCollection(store);
            String text = clip(summary, MAX_TEXT_CHARS);
            float[] dense = client.embed(text);
            if (dense == null || dense.length == 0) {
                // embedding 失败只丢"语义可搜"，不写半个点（混合集合要求 dense 与 sparse 同名向量都有值）
                log.warn("[summary-vector] 摘要向量化失败，跳过索引 sessionId={}", sessionId);
                return;
            }
            SparseVector sparse = SparseEncoder.encode(text);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", sessionId);
            payload.put("session_id", sessionId);
            payload.put("user_id", userId == null ? "" : userId);
            // 摘要原文进 payload：召回时直接渲染，不必再回表
            payload.put("summary", summary.strip());
            store.upsertHybrid(collectionName(), List.of(
                    new VectorStore.HybridPoint(POINT_PREFIX + sessionId, dense, sparse, payload)));
            log.info("[summary-vector] 摘要已索引 sessionId={} chars={}", sessionId, summary.length());
        } catch (Exception e) {
            log.warn("[summary-vector] 摘要索引失败（不影响本次对话）sessionId={}：{}", sessionId, e.getMessage());
        }
    }

    /**
     * 召回该用户其他会话里与本轮问题相关的摘要。
     *
     * @param userId           用户 ID
     * @param excludeSessionId 当前会话（要排除）
     * @param query            本轮用户输入
     * @param limit            最多返回条数
     * @return 命中的摘要；不可用/无命中/失败时返回空列表
     */
    public List<SummaryRecall> recall(String userId, String excludeSessionId, String query, int limit) {
        if (userId == null || userId.isBlank() || query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        VectorStore store = vectorStoreProvider.getIfAvailable();
        EmbeddingClient client = embeddingClientProvider.getIfAvailable();
        if (store == null || client == null || !client.available()) {
            return List.of();
        }
        try {
            float[] dense = client.embed(clip(query, MAX_TEXT_CHARS));
            if (dense == null || dense.length == 0) {
                // embedding 不可用时仍走稀疏召回：中文 2-gram 对"上次 502"这类字面线索足够用
                dense = null;
            }
            SparseVector sparse = SparseEncoder.encodeQuery(query);
            // 多取几条，因为要剔除"当前会话自己"
            int topK = Math.max(limit + 3, limit * 2);
            List<VectorStore.Hit> hits = store.queryHybrid(collectionName(), dense, sparse, topK,
                    Map.of("user_id", userId), null);
            if (hits == null || hits.isEmpty()) {
                return List.of();
            }
            List<SummaryRecall> result = new ArrayList<>(limit);
            for (VectorStore.Hit hit : hits) {
                if (result.size() >= limit) {
                    break;
                }
                Map<String, Object> payload = hit.payload();
                String sessionId = payload == null ? null : String.valueOf(payload.get("session_id"));
                String summary = payload == null ? null : String.valueOf(payload.get("summary"));
                if (sessionId == null || sessionId.isBlank() || sessionId.equals(excludeSessionId)) {
                    continue;
                }
                if (summary == null || summary.isBlank() || "null".equals(summary)) {
                    continue;
                }
                result.add(new SummaryRecall(sessionId, summary.strip(), hit.score()));
            }
            return result;
        } catch (Exception e) {
            log.warn("[summary-vector] 跨会话召回失败（不影响本次对话）：{}", e.getMessage());
            return List.of();
        }
    }

    private void ensureCollection(VectorStore store) {
        if (collectionReady) {
            return;
        }
        int dimensions = ragProperties.getEmbedding().getDimensions();
        store.createHybridCollectionIfAbsent(collectionName(), dimensions);
        store.ensurePayloadIndexes(collectionName());
        collectionReady = true;
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /**
     * 一条跨会话摘要命中。
     *
     * @param sessionId 来源会话
     * @param summary   摘要正文
     * @param score     融合分（仅用于排序/调试，不作为置信度）
     */
    public record SummaryRecall(String sessionId, String summary, double score) {
    }
}