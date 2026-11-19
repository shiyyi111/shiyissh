package com.shiyi.domain.agent.service.memory;

import com.shiyi.domain.agent.adapter.embedding.EmbeddingClient;
import com.shiyi.domain.agent.adapter.repository.ILongTermMemoryRepository;
import com.shiyi.domain.agent.adapter.vector.SparseVector;
import com.shiyi.domain.agent.adapter.vector.VectorStore;
import com.shiyi.domain.agent.model.entity.LongTermMemoryEntity;
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
 * 长期记忆的向量索引与语义召回。
 * <p>
 * 职责就两件事，两边都围绕同一个 Qdrant 集合：
 * <ul>
 *   <li><b>写</b>：{@link #indexPending} 把 MySQL 里待索引的记忆（{@code embedding_status=0/2}）
 *       批量向量化后写入混合集合（稠密 + 稀疏），成功后置 1，失败置 2 并累加尝试次数；</li>
 *   <li><b>读</b>：{@link #recallScores} 用"查询向量 + 稀疏词项"一次混合检索拿到语义候选，
 *       返回 {@code 记忆 id → 归一化相关度}，由 {@code LongTermMemoryService} 回表后用
 *       原有六因子打分做最终排序。</li>
 * </ul>
 * <p>
 * 为什么候选交给 Qdrant、排序仍留在 MySQL 侧：语义召回解决"同义不同词找不到"，
 * 但"该不该进上下文"还要看记忆类型、重要度、置信度、新鲜度、使用频次——这些是业务判断，
 * 不该塞进向量库。所以这里只负责"把可能相关的捞出来"，不负责"决定用哪条"。
 * <p>
 * 为什么集合里同时放稠密与稀疏：embedding 服务不可用时（欠费、限流）稀疏路仍可召回
 * 字面命中的记忆，宁可降级也不要整体失效。
 *
 * @see com.shiyi.domain.agent.service.memory.LongTermMemoryService
 */
@Service
public class MemoryVectorService {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorService.class);

    /** 集合名后缀：与知识库 {@code _knowledge_hybrid_} 区分开，维度也写在名字里便于换模型时并存 */
    private static final String COLLECTION_SUFFIX = "_memory_hybrid_";

    /** 单批索引上限：一次 embedBatch 最多这么多条，避免长事务与超长请求 */
    public static final int DEFAULT_INDEX_BATCH = 50;

    /** 召回候选上限：语义召回的候选池，最终由六因子打分再收窄到 topN */
    public static final int DEFAULT_RECALL_LIMIT = 50;

    /** 参与向量化的文本上限（与知识库同一口径，避免超长内容稀释主题） */
    private static final int MAX_TEXT_CHARS = 2000;

    /** payload 里的预览长度：召回后用于日志与人工核对，不参与检索 */
    private static final int PREVIEW_CHARS = 300;

    /** 记忆点在集合里的业务 id 前缀：同一集合只放记忆，前缀用于防御性解析 */
    private static final String POINT_PREFIX = "mem:";

    @Resource
    private ILongTermMemoryRepository longTermMemoryRepository;

    /**
     * 用 ObjectProvider 而不是直接注入：向量与 embedding 都可能被配置关掉
     * （{@code app.rag.vector.enabled=false}），此时容器里没有对应 Bean，
     * 直接注入会让整个应用起不来。
     */
    @Resource
    private ObjectProvider<EmbeddingClient> embeddingClientProvider;

    @Resource
    private ObjectProvider<VectorStore> vectorStoreProvider;

    @Resource
    private RagProperties ragProperties;

    /** 集合是否已确认存在（懒建一次，失败下次再试；不加锁，重复建集合是幂等的） */
    private volatile boolean collectionReady;

    /**
     * 当前是否具备语义召回条件：向量检索开启、embedding 可用、且容器里有向量库实现。
     * <p>
     * 注意这只看"配置与实现是否就绪"，不代表 Qdrant 此刻可达——可达性在每次调用时
     * 用异常兜住，不要让"探活"变成热路径上的额外一次网络往返。
     *
     * @return true 表示可以尝试语义召回
     */
    public boolean available() {
        if (!ragProperties.getVector().isEnabled() || !ragProperties.getEmbedding().isEnabled()) {
            return false;
        }
        EmbeddingClient client = embeddingClientProvider.getIfAvailable();
        return client != null && client.available() && vectorStoreProvider.getIfAvailable() != null;
    }

    /**
     * 记忆混合集合名，形如 {@code shiyissh_memory_hybrid_1024}。
     *
     * @return 集合名
     */
    public String collectionName() {
        int dimensions = dimensions();
        return ragProperties.getVector().getCollectionPrefix() + COLLECTION_SUFFIX + dimensions;
    }

    /**
     * 批量索引待向量化的记忆。
     * <p>
     * 失败语义按"批"处理：embedding 整批失败时所有条目 attempts+1（下次重试），
     * 单条写入失败只标记该条失败；任何一条坏数据都不应该拖住整批。
     *
     * @param limit 单批上限，{@code <=0} 时取 {@link #DEFAULT_INDEX_BATCH}
     * @return 本次成功索引的条数
     */
    public int indexPending(int limit) {
        if (!available()) {
            return 0;
        }
        int batch = limit <= 0 ? DEFAULT_INDEX_BATCH : limit;
        return indexEntities(longTermMemoryRepository.findPendingEmbedding(batch));
    }

    /**
     * 按主键索引一批记忆（写入路径的即时索引用）。
     * <p>
     * 与 {@link #indexPending} 的区别只是"挑哪批行"：这里由调用方给出 id，
     * 内容一律以数据库当前值为准——同一个 {@code memory_key} 连续更新两次时，
     * 后入队的任务读到的是最新内容，天然幂等，不会写出两份。
     *
     * @param ids 记忆主键
     * @return 成功写入向量库的条数
     */
    public int indexByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty() || !available()) {
            return 0;
        }
        return indexEntities(longTermMemoryRepository.findByIds(ids));
    }

    /**
     * 索引一批已经取出的记忆行（待索引池与写入路径共用）。
     *
     * @param pending 记忆行
     * @return 成功写入向量库的条数
     */
    private int indexEntities(List<LongTermMemoryEntity> pending) {
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        EmbeddingClient embeddingClient = embeddingClientProvider.getIfAvailable();
        if (!ensureCollection()) {
            return 0;
        }

        List<String> texts = new ArrayList<>(pending.size());
        for (LongTermMemoryEntity memory : pending) {
            texts.add(toVectorText(memory));
        }
        List<float[]> vectors = embeddingClient.embedBatch(texts);
        if (vectors == null || vectors.size() != pending.size()) {
            // 整批失败：逐条记一次失败，达上限后自然不再重试，避免坏数据反复打外部接口
            for (LongTermMemoryEntity memory : pending) {
                longTermMemoryRepository.markEmbeddingFailed(memory.getId());
            }
            log.warn("[memory-index] 批量向量化失败，{} 条记忆保持不变或累加失败次数", pending.size());
            return 0;
        }

        VectorStore store = vectorStoreProvider.getIfAvailable();
        String collection = collectionName();
        List<VectorStore.HybridPoint> points = new ArrayList<>(pending.size());
        for (int i = 0; i < pending.size(); i++) {
            LongTermMemoryEntity memory = pending.get(i);
            float[] dense = vectors.get(i);
            if (dense == null || dense.length == 0) {
                longTermMemoryRepository.markEmbeddingFailed(memory.getId());
                continue;
            }
            SparseVector sparse = SparseEncoder.encode(texts.get(i));
            points.add(new VectorStore.HybridPoint(POINT_PREFIX + memory.getId(), dense, sparse,
                    toPayload(memory)));
        }
        if (points.isEmpty()) {
            return 0;
        }
        try {
            store.upsertHybrid(collection, points);
        } catch (Exception e) {
            for (VectorStore.HybridPoint point : points) {
                longTermMemoryRepository.markEmbeddingFailed(idOf(point.id()));
            }
            log.warn("[memory-index] 写入向量库失败 collection={}：{}", collection, e.getMessage());
            return 0;
        }
        for (VectorStore.HybridPoint point : points) {
            longTermMemoryRepository.markEmbedded(idOf(point.id()));
        }
        log.info("[memory-index] op=index collection={} indexed={} skipped={}",
                collection, points.size(), pending.size() - points.size());
        return points.size();
    }

    /**
     * 语义召回：返回"记忆 id → 归一化相关度（0~1）"，按相关度降序。
     * <p>
     * 归一化用本次结果的最大分做分母：不同 query 的绝对分不可比（BM25/余弦尺度随查询长度变化），
     * 而六因子打分里的"覆盖率"必须在 0~1 区间才有可比性。
     * <p>
     * 任何异常（集合不存在、Qdrant 不可达、embedding 超时）都返回空 Map，
     * 由调用方退回原来的关键词候选池——记忆是增强项，不能因为它让主流程失败。
     *
     * @param userId  用户 ID
     * @param hostKey 当前主机标识，可为空
     * @param query   用户查询原文
     * @param limit   候选上限，{@code <=0} 时取 {@link #DEFAULT_RECALL_LIMIT}
     * @return 有序 Map：记忆 id → 归一化相关度
     */
    public Map<Long, Double> recallScores(String userId, String hostKey, String query, int limit) {
        if (userId == null || userId.isBlank() || query == null || query.isBlank() || !available()) {
            return Map.of();
        }
        EmbeddingClient embeddingClient = embeddingClientProvider.getIfAvailable();
        float[] dense = embeddingClient.embed(query);
        SparseVector sparse = SparseEncoder.encodeQuery(query);
        boolean hasDense = dense != null && dense.length > 0;
        if (!hasDense && sparse.isEmpty()) {
            return Map.of();
        }
        VectorStore store = vectorStoreProvider.getIfAvailable();
        int topK = limit <= 0 ? DEFAULT_RECALL_LIMIT : limit;
        Map<String, String> must = Map.of("user_id", userId);
        // 归属口径：跨主机的用户偏好 ∪ 本机事实；其它机器上的 HOST 记忆不应出现在本机候选里
        List<Map<String, String>> should = new ArrayList<>(2);
        should.add(Map.of("scope", "USER"));
        if (hostKey != null && !hostKey.isBlank()) {
            should.add(Map.of("host_key", hostKey));
        }
        List<VectorStore.Hit> hits;
        try {
            hits = store.queryHybrid(collectionName(), hasDense ? dense : null, sparse, topK, must, should);
        } catch (Exception e) {
            log.debug("[memory-recall] 语义候选不可用，回退关键词候选池：{}", e.getMessage());
            return Map.of();
        }
        if (hits == null || hits.isEmpty()) {
            return Map.of();
        }
        double max = 0d;
        for (VectorStore.Hit hit : hits) {
            max = Math.max(max, hit.score());
        }
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (VectorStore.Hit hit : hits) {
            Long id = idOf(hit.id());
            if (id == null) {
                continue;
            }
            double normalized = max <= 0d ? 0d : Math.min(1d, hit.score() / max);
            scores.putIfAbsent(id, normalized);
        }
        log.info("[memory-recall] op=semantic userId={} hostKey={} candidates={} hasDense={}",
                userId, hostKey, scores.size(), hasDense);
        return scores;
    }

    /**
     * 确保混合集合存在（含 payload 索引）。懒建一次，失败后下次调用会重试。
     *
     * @return true 表示集合可用
     */
    private boolean ensureCollection() {
        if (collectionReady) {
            return true;
        }
        VectorStore store = vectorStoreProvider.getIfAvailable();
        if (store == null) {
            return false;
        }
        String collection = collectionName();
        try {
            store.createHybridCollectionIfAbsent(collection, dimensions());
            store.ensurePayloadIndexes(collection);
            collectionReady = true;
            return true;
        } catch (Exception e) {
            log.warn("[memory-index] 建集合失败 collection={}：{}", collection, e.getMessage());
            return false;
        }
    }

    /** 记忆参与向量化的文本：类型 + 内容 + 关键词，让"软件版本"这类词也进入稀疏路。 */
    private String toVectorText(LongTermMemoryEntity memory) {
        StringBuilder builder = new StringBuilder();
        append(builder, memory.getMemoryType());
        append(builder, memory.getContent());
        append(builder, memory.getKeywords());
        String text = builder.toString().trim();
        return text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text;
    }

    /** payload 只放过滤字段与预览，不放正文：正文以 MySQL 为准，避免两份数据漂移。 */
    private Map<String, Object> toPayload(LongTermMemoryEntity memory) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", String.valueOf(memory.getId()));
        payload.put("memory_type", nullSafe(memory.getMemoryType()));
        payload.put("scope", nullSafe(memory.getScope()));
        payload.put("user_id", nullSafe(memory.getUserId()));
        payload.put("host_key", nullSafe(memory.getHostKey()));
        String content = nullSafe(memory.getContent());
        payload.put("preview", content.length() > PREVIEW_CHARS ? content.substring(0, PREVIEW_CHARS) : content);
        return payload;
    }

    private static void append(StringBuilder builder, String value) {
        if (value != null && !value.isBlank()) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(value);
        }
    }

    private int dimensions() {
        int dimensions = ragProperties.getEmbedding().getDimensions();
        return dimensions > 0 ? dimensions : 1024;
    }

    /** 从点 id（{@code mem:123}）解析记忆主键；格式不符返回 null，避免脏数据带崩整批。 */
    private static Long idOf(String pointId) {
        if (pointId == null || !pointId.startsWith(POINT_PREFIX)) {
            return null;
        }
        try {
            return Long.parseLong(pointId.substring(POINT_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}