package com.shiyi.infrastructure.adapter.vector;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.shiyi.domain.agent.adapter.vector.VectorStore;
import com.shiyi.domain.agent.adapter.vector.SparseVector;
import com.shiyi.domain.agent.model.valobj.properties.RagProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Qdrant 向量库（REST 手写客户端）。
 * <p>
 * 为什么手写 HTTP 而不引 {@code io.qdrant:client} 或 {@code spring-ai-qdrant-store}：
 * 这里只用到 4 个接口（建集合 / 写入 / 检索 / 删除），引一套客户端要拖进 gRPC、protobuf 等依赖，
 * 而 Qdrant 的 REST 契约非常稳定。REST 的代价是每次多一次 HTTP 往返，
 * 但检索本身就已经要跨网络，这点开销可以忽略；换来的是依赖零增长、出问题能直接抓包看。
 * <p>
 * 注意：Qdrant 部署在虚拟机里时 {@code app.rag.vector.host} 要填虚拟机 IP，
 * 不能填 localhost —— 那是 Windows 本机，指不到虚拟机。
 *
 * @author shiyi
 */
@Service
@ConditionalOnProperty(name = "app.rag.vector.store", havingValue = "qdrant", matchIfMissing = true)
public class QdrantVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);

    /** payload 里保存业务主键的字段名：Qdrant 的 point id 只接受 uint64 或 UUID，业务 id 只能放 payload。 */
    private static final String PAYLOAD_ORIG_ID = "orig_id";

    @Resource
    private RagProperties ragProperties;

    private HttpClient httpClient;

    @PostConstruct
    public void init() {
        RagProperties.Vector vector = ragProperties.getVector();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(vector.getTimeoutMs()))
                .build();
        log.info("[qdrant] 已配置地址 {}", baseUrl());
    }

    @Override
    public boolean healthy() {
        try {
            HttpResponse<String> response = send("GET", "/collections", null);
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            log.warn("[qdrant] 健康检查失败：{}", e.getMessage());
            return false;
        }
    }

    @Override
    public void createCollectionIfAbsent(String collection, int dimensions) {
        JSONObject vectors = new JSONObject();
        vectors.put("size", dimensions);
        vectors.put("distance", "Cosine");
        JSONObject body = new JSONObject();
        body.put("vectors", vectors);
        try {
            HttpResponse<String> response = send("PUT", "/collections/" + collection, body.toJSONString());
            if (response.statusCode() == 409) {
                log.debug("[qdrant] 集合 {} 已存在", collection);
                return;
            }
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("建集合失败：" + response.statusCode() + " " + response.body());
            }
            log.info("[qdrant] 已创建集合 {}（维度 {}）", collection, dimensions);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("建集合失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void upsert(String collection, List<Point> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        JSONArray array = new JSONArray();
        for (Point point : points) {
            JSONObject item = new JSONObject();
            item.put("id", toPointId(point.id()));
            item.put("vector", toArray(point.vector()));
            Map<String, Object> payload = new LinkedHashMap<>();
            if (point.payload() != null) {
                payload.putAll(point.payload());
            }
            payload.put(PAYLOAD_ORIG_ID, point.id());
            item.put("payload", payload);
            array.add(item);
        }
        JSONObject body = new JSONObject();
        body.put("points", array);
        try {
            HttpResponse<String> response = send("PUT", "/collections/" + collection + "/points?wait=true", body.toJSONString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("写入失败：" + response.statusCode() + " " + response.body());
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("写入失败：" + e.getMessage(), e);
        }
    }


    @Override
    public void delete(String collection, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        JSONArray points = new JSONArray();
        for (String id : ids) {
            points.add(toPointId(id));
        }
        JSONObject body = new JSONObject();
        body.put("points", points);
        try {
            HttpResponse<String> response = send("POST", "/collections/" + collection + "/points/delete?wait=true", body.toJSONString());
            if (response.statusCode() >= 300) {
                log.warn("[qdrant] 删除失败：{} {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("[qdrant] 删除失败：{}", e.getMessage());
        }
    }

    @Override
    public void createHybridCollectionIfAbsent(String collection, int dimensions) {
        JSONObject dense = new JSONObject();
        dense.put("size", dimensions);
        dense.put("distance", "Cosine");
        JSONObject vectors = new JSONObject();
        vectors.put("dense", dense);
        // sparse 用 modifier=idf：客户端只送词频，IDF 由 Qdrant 按全库文档频次现算，
        // 新增文档不需要重建历史向量；同时检索时也由服务端套用同一份 IDF，评分口径一致。
        JSONObject sparse = new JSONObject();
        sparse.put("modifier", "idf");
        JSONObject sparseVectors = new JSONObject();
        sparseVectors.put("sparse", sparse);
        JSONObject body = new JSONObject();
        body.put("vectors", vectors);
        body.put("sparse_vectors", sparseVectors);
        try {
            HttpResponse<String> response = send("PUT", "/collections/" + collection, body.toJSONString());
            if (response.statusCode() == 409) {
                log.debug("[qdrant] 混合集合 {} 已存在", collection);
                return;
            }
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("建混合集合失败：" + response.statusCode() + " " + response.body());
            }
            log.info("[qdrant] 已创建混合集合 {}（维度 {}，dense+sparse）", collection, dimensions);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // 网络类异常（如 ConnectException）的 message 常为 null，只拼 e.getMessage() 会得到
            // "建混合集合失败：null"，既看不出是连不上也看不出连的是谁，这里补上异常类型与目标地址。
            String detail = e.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = e.getClass().getSimpleName();
            }
            throw new IllegalStateException(
                    "建混合集合失败（" + baseUrl() + "/collections/" + collection + "）：" + detail, e);
        }
    }

    @Override
    public List<String> listDocIds(String collection, String source) {
        List<String> result = new ArrayList<>();
        if (!healthy()) {
            return result;
        }
        Object offset = null;
        for (int guard = 0; guard < 100; guard++) {
            JSONObject body = new JSONObject();
            JSONObject match = new JSONObject();
            match.put("key", "source");
            match.put("match", new JSONObject(Map.of("value", source)));
            JSONObject filter = new JSONObject();
            filter.put("must", new JSONArray(List.of(match)));
            body.put("filter", filter);
            body.put("limit", 200);
            body.put("with_payload", List.of("doc_id"));
            body.put("with_vector", false);
            if (offset != null) {
                body.put("offset", offset);
            }
            try {
                HttpResponse<String> response = send("POST",
                        "/collections/" + collection + "/points/scroll", body.toJSONString());
                if (response.statusCode() >= 300) {
                    log.warn("[qdrant] 列举 doc_id 失败：{} {}", response.statusCode(), response.body());
                    break;
                }
                JSONObject resultObj = JSON.parseObject(response.body()).getJSONObject("result");
                if (resultObj == null) {
                    break;
                }
                JSONArray points = resultObj.getJSONArray("points");
                if (points != null) {
                    for (int i = 0; i < points.size(); i++) {
                        JSONObject payload = points.getJSONObject(i).getJSONObject("payload");
                        String docId = payload == null ? null : payload.getString("doc_id");
                        if (docId != null && !docId.isBlank()) {
                            result.add(docId);
                        }
                    }
                }
                offset = resultObj.get("next_page_offset");
                if (offset == null || points == null || points.isEmpty()) {
                    break;
                }
            } catch (Exception e) {
                log.warn("[qdrant] 列举 doc_id 失败：{}", e.getMessage());
                break;
            }
        }
        return result;
    }

    @Override
    public void ensurePayloadIndexes(String collection) {
        // payload 索引只影响过滤性能，建失败不影响正确性：全部吞掉只记 warn，
        // 避免因为 Qdrant 版本差异（比如不支持某个 schema 参数）把整条索引链路搞挂。
        String[] keys = {"source", "scope", "kind", "user_id", "host_key", "doc_id"};
        for (String key : keys) {
            JSONObject fieldSchema = new JSONObject();
            fieldSchema.put("type", "keyword");
            JSONObject body = new JSONObject();
            body.put("field_name", key);
            body.put("field_schema", fieldSchema);
            try {
                HttpResponse<String> response = send("PUT",
                        "/collections/" + collection + "/index?wait=true", body.toJSONString());
                if (response.statusCode() >= 300) {
                    log.debug("[qdrant] 创建 payload 索引 {} 失败：{} {}", key, response.statusCode(), response.body());
                }
            } catch (Exception e) {
                log.debug("[qdrant] 创建 payload 索引 {} 异常：{}", key, e.getMessage());
            }
        }
    }

    @Override
    public void upsertHybrid(String collection, List<HybridPoint> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        JSONArray array = new JSONArray();
        for (HybridPoint point : points) {
            JSONObject item = new JSONObject();
            item.put("id", toPointId(point.id()));
            JSONObject vector = new JSONObject();
            // named vector 可以缺省：embedding 挂掉时先写"仅稀疏"的点，检索走稀疏路，
            // 等 embedding 恢复后全量重同步再把 dense 补上（同一个点 id 覆盖）。
            if (point.dense() != null) {
                vector.put("dense", toArray(point.dense()));
            }
            SparseVector sparse = point.sparse();
            if (sparse != null && !sparse.isEmpty()) {
                JSONObject sparseObject = new JSONObject();
                JSONArray indices = new JSONArray(sparse.indices().length);
                for (long index : sparse.indices()) {
                    indices.add(index);
                }
                JSONArray values = new JSONArray(sparse.values().length);
                for (float value : sparse.values()) {
                    values.add(value);
                }
                sparseObject.put("indices", indices);
                sparseObject.put("values", values);
                vector.put("sparse", sparseObject);
            }
            if (!vector.isEmpty()) {
                item.put("vector", vector);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            if (point.payload() != null) {
                payload.putAll(point.payload());
            }
            payload.put(PAYLOAD_ORIG_ID, point.id());
            item.put("payload", payload);
            array.add(item);
        }
        JSONObject body = new JSONObject();
        body.put("points", array);
        try {
            HttpResponse<String> response = send("PUT", "/collections/" + collection + "/points?wait=true", body.toJSONString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("混合写入失败：" + response.statusCode() + " " + response.body());
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("混合写入失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<Hit> queryHybrid(String collection, float[] dense, SparseVector sparse, int topK,
                                 Map<String, String> filter) {
        return queryHybrid(collection, dense, sparse, topK, filter, null);
    }

    @Override
    public List<Hit> queryHybrid(String collection, float[] dense, SparseVector sparse, int topK,
                                 Map<String, String> filter,
                                 List<Map<String, String>> shouldConditions) {
        boolean hasDense = dense != null && dense.length > 0;
        boolean hasSparse = sparse != null && !sparse.isEmpty();
        if (!hasDense && !hasSparse) {
            return List.of();
        }
        JSONObject body = new JSONObject();
        JSONArray prefetch = new JSONArray();
        if (hasDense) {
            JSONObject densePrefetch = new JSONObject();
            densePrefetch.put("query", toArray(dense));
            densePrefetch.put("using", "dense");
            // 召回窗口比最终条数宽：融合后取前 topK，单路各取 topK 会让"两路都稍靠后但一致命中"的条目被挤掉
            densePrefetch.put("limit", Math.max(topK * 2, 20));
            prefetch.add(densePrefetch);
        }
        JSONObject sparseQuery = null;
        if (hasSparse) {
            sparseQuery = new JSONObject();
            JSONArray indices = new JSONArray(sparse.indices().length);
            for (long index : sparse.indices()) {
                indices.add(index);
            }
            JSONArray values = new JSONArray(sparse.values().length);
            for (float value : sparse.values()) {
                values.add(value);
            }
            sparseQuery.put("indices", indices);
            sparseQuery.put("values", values);
            JSONObject sparsePrefetch = new JSONObject();
            sparsePrefetch.put("query", sparseQuery);
            sparsePrefetch.put("using", "sparse");
            sparsePrefetch.put("limit", Math.max(topK * 2, 20));
            prefetch.add(sparsePrefetch);
        }
        if (prefetch.size() > 1) {
            body.put("prefetch", prefetch);
            JSONObject query = new JSONObject();
            // 两路才需要 RRF；单路直接返回原分数，避免分数语义变形
            query.put("fusion", "rrf");
            body.put("query", query);
        } else if (hasDense) {
            body.put("prefetch", prefetch);
            JSONObject query = new JSONObject();
            query.put("nearest", toArray(dense));
            body.put("query", query);
        } else {
            // 只有稀疏：用顶层 query + using=sparse，这是 Qdrant 的稀疏检索标准写法，
            // 套一层 prefetch/fusion 反而会多一次无意义的融合（embedding 不可用时走这里）
            body.put("query", sparseQuery);
            body.put("using", "sparse");
        }
        body.put("limit", topK);
        body.put("with_payload", true);
        JSONObject queryFilter = toQueryFilter(filter, shouldConditions);
        if (queryFilter != null) {
            body.put("filter", queryFilter);
        }
        try {
            HttpResponse<String> response = send("POST",
                    "/collections/" + collection + "/points/query", body.toJSONString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("混合检索失败：" + response.statusCode() + " " + response.body());
            }
            return parseQueryHits(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("混合检索失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<Hit> search(String collection, float[] query, int topK, Map<String, String> filter) {
        if (query == null || query.length == 0) {
            return List.of();
        }
        JSONObject body = new JSONObject();
        body.put("vector", toArray(query));
        body.put("limit", topK);
        body.put("with_payload", true);
        if (filter != null && !filter.isEmpty()) {
            body.put("filter", toFilter(filter));
        }
        try {
            HttpResponse<String> response = send("POST", "/collections/" + collection + "/points/search", body.toJSONString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("检索失败：" + response.statusCode() + " " + response.body());
            }
            return parseHits(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("检索失败：" + e.getMessage(), e);
        }
    }

    /**
     * 把等值过滤条件翻译成 Qdrant 的 filter 结构。
     * <p>
     * 抽出来是因为三处（search / queryHybrid / 以后可能的分页）要用同一份翻译，
     * 各写一遍最容易出现"某处漏了 must"这种安静的错误。
     */
    private JSONObject toFilter(Map<String, String> filter) {
        JSONArray must = new JSONArray();
        for (Map.Entry<String, String> entry : filter.entrySet()) {
            JSONObject match = new JSONObject();
            match.put("value", entry.getValue());
            JSONObject condition = new JSONObject();
            condition.put("key", entry.getKey());
            condition.put("match", match);
            must.add(condition);
        }
        JSONObject filterObject = new JSONObject();
        filterObject.put("must", must);
        return filterObject;
    }

    /**
     * 翻译"必须满足 + 命中任一"的组合过滤（记忆召回用）。
     * <p>
     * 语义：{@code must} 全部满足，且 {@code should} 至少满足一条。
     * 这是"scope=USER 或 host_key=本机"这种归属口径的唯一正确表达方式——
     * 拆成两次查询再合并，会把 Qdrant 的融合排序机制绕过去。
     *
     * @param mustFilter       必须同时满足的等值条件，可为空
     * @param shouldConditions 命中任一即可的等值条件，可为空
     * @return 过滤对象；两个条件都为空时返回 {@code null}（不发 filter 字段）
     */
    private JSONObject toQueryFilter(Map<String, String> mustFilter,
                                     List<Map<String, String>> shouldConditions) {
        boolean hasMust = mustFilter != null && !mustFilter.isEmpty();
        boolean hasShould = shouldConditions != null && !shouldConditions.isEmpty();
        if (!hasMust && !hasShould) {
            return null;
        }
        JSONObject filterObject = new JSONObject();
        if (hasMust) {
            filterObject.put("must", toConditions(mustFilter));
        }
        if (hasShould) {
            JSONArray should = new JSONArray();
            for (Map<String, String> condition : shouldConditions) {
                should.addAll(toConditions(condition));
            }
            filterObject.put("should", should);
        }
        return filterObject;
    }

    private JSONArray toConditions(Map<String, String> conditions) {
        JSONArray array = new JSONArray();
        for (Map.Entry<String, String> entry : conditions.entrySet()) {
            JSONObject match = new JSONObject();
            match.put("value", entry.getValue());
            JSONObject condition = new JSONObject();
            condition.put("key", entry.getKey());
            condition.put("match", match);
            array.add(condition);
        }
        return array;
    }

    /**
     * 解析 {@code /points/query} 的返回：结构是 {@code {"result":{"points":[...]}}}，
     * 与老接口 {@code /points/search} 的 {@code {"result":[...]}} 不同，不能复用 parseHits。
     */
    private List<Hit> parseQueryHits(String responseBody) {
        JSONObject root = JSON.parseObject(responseBody);
        JSONObject result = root == null ? null : root.getJSONObject("result");
        JSONArray points = result == null ? null : result.getJSONArray("points");
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            JSONObject item = points.getJSONObject(i);
            JSONObject payload = item.getJSONObject("payload");
            String origId = payload == null ? null : payload.getString(PAYLOAD_ORIG_ID);
            Map<String, Object> payloadMap = payload == null ? Map.of() : new LinkedHashMap<>(payload);
            hits.add(new Hit(origId == null ? item.getString("id") : origId,
                    item.getDoubleValue("score"), payloadMap));
        }
        return hits;
    }

    private List<Hit> parseHits(String responseBody) {
        JSONObject root = JSON.parseObject(responseBody);
        JSONArray result = root == null ? null : root.getJSONArray("result");
        if (result == null || result.isEmpty()) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>(result.size());
        for (int i = 0; i < result.size(); i++) {
            JSONObject item = result.getJSONObject(i);
            JSONObject payload = item.getJSONObject("payload");
            String origId = payload == null ? null : payload.getString(PAYLOAD_ORIG_ID);
            Map<String, Object> payloadMap = payload == null ? Map.of() : new LinkedHashMap<>(payload);
            hits.add(new Hit(origId == null ? item.getString("id") : origId,
                    item.getDoubleValue("score"), payloadMap));
        }
        return hits;
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .timeout(Duration.ofMillis(ragProperties.getVector().getTimeoutMs()))
                .header("Content-Type", "application/json");
        String apiKey = ragProperties.getVector().getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("api-key", apiKey);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String baseUrl() {
        RagProperties.Vector vector = ragProperties.getVector();
        return "http://" + vector.getHost() + ":" + vector.getPort();
    }

    /**
     * 把业务主键映射成 Qdrant 可接受的 UUID。
     * 用 nameUUIDFromBytes 是确定性的：同一个 docId 永远得到同一个 point id，重复导入自然覆盖。
     */
    private static String toPointId(String id) {
        return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static JSONArray toArray(float[] vector) {
        JSONArray array = new JSONArray(vector.length);
        for (float value : vector) {
            array.add(value);
        }
        return array;
    }
}