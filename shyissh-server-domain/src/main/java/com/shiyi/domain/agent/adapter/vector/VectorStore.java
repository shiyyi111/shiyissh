package com.shiyi.domain.agent.adapter.vector;

import java.util.List;
import java.util.Map;

/**
 * 向量库端口。
 * <p>
 * 只承担"存 id → 向量 + payload、按相似度取 TopK"这几件事，不负责生成向量、不负责业务过滤逻辑、
 * 不负责正文存储（正文永远在 MySQL，向量库只存 id 与少量用于过滤的副本字段）。
 * <p>
 * 这样切分的理由：
 * <ul>
 *   <li><b>向量库不是主库</b>：Qdrant/Milvus 丢数据不影响业务，重建即可（MySQL 是 source of truth）；</li>
 *   <li><b>可换实现</b>：现在用 Qdrant，条目少时也可以退回"MySQL 存 BLOB + JVM 余弦"
 *       的实现，接口不变；</li>
 *   <li><b>可降级</b>：{@link #healthy()} 为 false 时，检索退回关键词，
 *       调用方不需要判断具体是哪种存储。</li>
 * </ul>
 *
 * @author shiyi
 */
public interface VectorStore {

    /** 向量库是否可用（连接正常）。不可用时检索链路降级为纯关键词。 */
    boolean healthy();

    /** 集合不存在则创建；维度与 {@code dimensions} 不一致时会被拒绝，因此换 embedding 模型要同时换集合名。 */
    void createCollectionIfAbsent(String collection, int dimensions);

    /**
     * 批量写入或覆盖。
     *
     * @param collection 集合名
     * @param points     待写入点；id 相同则覆盖
     */
    void upsert(String collection, List<Point> points);

    /**
     * 相似度检索。
     *
     * @param collection 集合名
     * @param query      查询向量，长度必须等于集合维度
     * @param topK       返回条数
     * @param filter     payload 等值过滤条件（如 {@code host_key}、{@code userId}），空 Map 表示不过滤
     * @return 按相似度降序的结果；失败返回空列表
     */
    List<Hit> search(String collection, float[] query, int topK, Map<String, String> filter);

    /**
     * 列出集合里某个来源的全部业务 id（{@code payload.doc_id}）。
     * <p>
     * 只给"源头同步"用：md 源是全量对比后增量写，需要知道索引里还有哪些旧条目要删。
     * 默认返回空列表——纯稠密实现不承担这个职责，调用方会把"空"当成"无法判定"并跳过清理。
     *
     * @param collection 集合名
     * @param source     payload.source 取值（如 {@code md}）
     * @return 业务 id 列表
     */
    default List<String> listDocIds(String collection, String source) {
        return List.of();
    }

    /** 按 id 删除；用于条目更新后重建、或删除失效知识。 */
    void delete(String collection, List<String> ids);

    /**
     * 一个待写入的点。
     *
     * @param id      业务主键（如 {@code wiki:123}、{@code kb:KB-001}），与 MySQL 主键对应
     * @param vector  向量
     * @param payload 用于过滤与展示的少量字段（不要放长正文）
     */
    record Point(String id, float[] vector, Map<String, Object> payload) {
    }

    /**
     * 一条检索命中。
     *
     * @param id      业务主键
     * @param score   相似度（越大越相关；Qdrant 余弦距离为 1-距离）
     * @param payload 写入时的 payload
     */
    record Hit(String id, double score, Map<String, Object> payload) {
    }

    /**
     * 一个待写入的混合点：稠密向量负责语义、稀疏向量负责精确词项。
     *
     * @param id      业务主键
     * @param dense   稠密向量
     * @param sparse  稀疏向量（可空，等价于只写稠密）
     * @param payload 过滤与展示字段
     */
    record HybridPoint(String id, float[] dense, SparseVector sparse, Map<String, Object> payload) {
    }

    /**
     * 创建"稠密 + 稀疏"混合集合。
     * <p>
     * 默认实现退化成纯稠密集合：接口存在是为了让检索层只依赖能力、不依赖 Qdrant，
     * 换回其他实现时仍能编译和运行（只是没有稀疏路）。
     */
    default void createHybridCollectionIfAbsent(String collection, int dimensions) {
        createCollectionIfAbsent(collection, dimensions);
    }

    /**
     * 为混合集合补 payload 索引。
     * <p>
     * 过滤字段（source/scope/user_id/host_key/kind）不建索引也能查，但会退化成全扫描；
     * 默认空实现，纯稠密实现无需关心。
     */
    default void ensurePayloadIndexes(String collection) {
    }

    /**
     * 批量写入混合点（稠密 + 稀疏）。
     * <p>
     * 默认实现只写稠密向量：老实现（无稀疏能力）仍然可用，代价是关键词命中变弱。
     */
    default void upsertHybrid(String collection, java.util.List<HybridPoint> points) {
        java.util.List<Point> denseOnly = new java.util.ArrayList<>(points.size());
        for (HybridPoint point : points) {
            denseOnly.add(new Point(point.id(), point.dense(), point.payload()));
        }
        upsert(collection, denseOnly);
    }

    /**
     * 混合检索：一次请求内做稠密 + 稀疏两路召回并融合。
     * <p>
     * 默认实现退化为纯稠密检索；Qdrant 实现用 {@code /points/query} 的 prefetch + RRF。
     *
     * @param collection 集合名
     * @param dense      查询稠密向量
     * @param sparse     查询稀疏向量；空向量时只走稠密路
     * @param topK       返回条数
     * @param filter     payload 等值过滤
     */
    default java.util.List<Hit> queryHybrid(String collection, float[] dense, SparseVector sparse, int topK,
                                            Map<String, String> filter) {
        return search(collection, dense, topK, filter);
    }

    /**
     * 混合检索（带 OR 条件）。
     * <p>
     * 等值过滤表达不了"scope=USER <b>或</b> host_key=本机"这类归属口径：
     * 记忆既有跨主机通用的偏好，也有只在本机成立的 HOST 事实，两者必须同时可见。
     * {@code mustFilter} 里的条件是与关系，{@code shouldConditions} 里的条件互斥、命中任一即可。
     * <p>
     * 默认实现忽略 OR 条件退化成等值过滤，只适合不关心归属的后端；
     * Qdrant 实现会翻译成 {@code must + should}。
     *
     * @param collection        集合名
     * @param dense             稠密向量，可为 null
     * @param sparse            稀疏向量，可为 null
     * @param topK              返回条数
     * @param mustFilter        必须同时满足的等值条件
     * @param shouldConditions  命中任一即可的等值条件
     * @return 命中列表，按相关度降序
     */
    default java.util.List<Hit> queryHybrid(String collection, float[] dense, SparseVector sparse, int topK,
                                            Map<String, String> mustFilter,
                                            java.util.List<Map<String, String>> shouldConditions) {
        return queryHybrid(collection, dense, sparse, topK, mustFilter);
    }
}