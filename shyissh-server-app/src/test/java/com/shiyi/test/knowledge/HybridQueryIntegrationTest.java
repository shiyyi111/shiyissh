package com.shiyi.test.knowledge;

import com.shiyi.domain.agent.adapter.vector.SparseVector;
import com.shiyi.domain.agent.adapter.vector.VectorStore;
import com.shiyi.domain.agent.model.valobj.properties.RagProperties;
import com.shiyi.infrastructure.adapter.vector.QdrantVectorStore;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 混合检索的真实 Qdrant 集成测试（named vectors + sparse {@code modifier: idf} + {@code /points/query} 融合）。
 * <p>
 * 为什么必须连真库：P1 的核心假设是"Qdrant 能在一次请求里同时走 dense 与 sparse 并融合"，
 * 这条假设只能靠真服务验证——mock 出来的 200 不能证明 API 形状、named vector 命名、
 * sparse 的 {@code indices/values} 序列化、融合分返回位置都是对的。
 * <p>
 * 不污染业务集合：用 {@code scratch_it_<时间戳>} 临时集合，测完把点删掉（集合本身留空，不影响检索）。
 * Qdrant 连不上时 {@code Assume} 跳过，不让没环境的机器红一片。
 *
 * @author shiyi
 */
public class HybridQueryIntegrationTest {

    /** 默认本机；Qdrant 在虚拟机里时用 {@code QDRANT_HOST=192.0.2.10} 之类的内网地址覆盖。 */
    private static final String HOST = System.getenv().getOrDefault("QDRANT_HOST", "localhost");
    private static final String COLLECTION_PREFIX = "scratch_it_";

    private static QdrantVectorStore store;
    private static String collection;
    private static boolean reachable;

    @BeforeClass
    public static void setUp() {
        RagProperties ragProperties = new RagProperties();
        ragProperties.getVector().setHost(HOST);
        ragProperties.getVector().setPort(6333);
        ragProperties.getVector().setTimeoutMs(3000);
        store = new QdrantVectorStore();
        setField(store, "ragProperties", ragProperties);
        store.init();
        reachable = store.healthy();
        Assume.assumeTrue("Qdrant 不可达（" + HOST + ":6333），跳过集成测试", reachable);
        collection = COLLECTION_PREFIX + System.currentTimeMillis();
        store.createHybridCollectionIfAbsent(collection, 4);
        store.ensurePayloadIndexes(collection);
        // 数据在 @BeforeClass 写一次：JUnit4 的方法执行顺序不确定，
        // 把 upsert 放进某个测试里会让另一个测试偶发失败（第一次写这个测试就踩了）
        seed();
    }

    private static void seed() {
        float[] diskDense = new float[]{1.0f, 0.0f, 0.0f, 0.0f};
        float[] nginxDense = new float[]{0.0f, 1.0f, 0.0f, 0.0f};
        store.upsertHybrid(collection, List.of(
                new VectorStore.HybridPoint("kv:1", diskDense, SparseEncoderAccess.encode("磁盘空间不足 df -h"),
                        Map.of("id", 1L, "source", "kb", "doc_id", "KB-001")),
                new VectorStore.HybridPoint("kv:2", nginxDense, SparseEncoderAccess.encode("nginx 502 bad gateway"),
                        Map.of("id", 2L, "source", "kb", "doc_id", "KB-010"))));
    }

    @AfterClass
    public static void tearDown() {
        if (reachable && collection != null) {
            store.delete(collection, List.of("kv:1", "kv:2"));
            // 临时集合本身也删掉：留一堆空集合，运维看 /collections 时会以为有脏数据
            dropCollection(collection);
        }
    }

    private static void dropCollection(String name) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://" + HOST + ":6333/collections/" + name))
                    .DELETE()
                    .build();
            HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.out.println("[it] 删除临时集合失败（不影响结论）：" + e.getMessage());
        }
    }

    @Test
    public void hybridQueryShouldFuseDenseAndSparseAndReturnPayload() {
        float[] diskDense = new float[]{1.0f, 0.0f, 0.0f, 0.0f};
        // 稠密命中"磁盘"，稀疏查询词也是"磁盘"：两路都指向 kv:1，融合后应排第一
        List<VectorStore.Hit> hits = store.queryHybrid(collection, diskDense,
                SparseEncoderAccess.encodeQuery("磁盘"), 5, Map.of());

        assertNotNull(hits);
        assertFalse("两路都命中时不应返回空", hits.isEmpty());
        assertEquals("kv:1", hits.get(0).payload().get("orig_id"));
        assertEquals("KB-001", hits.get(0).payload().get("doc_id"));
        assertTrue("融合分应为正数", hits.get(0).score() > 0d);
    }

    @Test
    public void sparseOnlyNeighbourShouldStillBeRetrieved() {
        float[] unrelatedDense = new float[]{0.0f, 0.0f, 1.0f, 0.0f};
        List<VectorStore.Hit> hits = store.queryHybrid(collection, unrelatedDense,
                SparseEncoderAccess.encodeQuery("bad gateway"), 5, Map.of());

        assertFalse("稠密不相关但稀疏命中时，稀疏路应把它捞回来", hits.isEmpty());
        assertTrue("命中里应有 nginx 那条", hits.stream()
                .anyMatch(hit -> "kv:2".equals(hit.payload().get("orig_id"))));
    }

    @Test
    public void denseMissingShouldStillAnswerFromSparseRoute() {
        // embedding 欠费/超时时客户端返回空向量，检索不能整条失败：稀疏路是本地计算，必须还能出结果
        List<VectorStore.Hit> hits = store.queryHybrid(collection, null,
                SparseEncoderAccess.encodeQuery("磁盘"), 5, Map.of());

        assertFalse("稠密向量缺失时稀疏路仍应返回命中", hits.isEmpty());
        assertEquals("kv:1", hits.get(0).payload().get("orig_id"));
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("注入失败：" + name, e);
        }
    }

    /**
     * SparseEncoder 在 domain 的 impl 包里是 public 的，但 {@code encodeQuery} 是静态 public，
     * 这里直接调用；单独抽一层只是为了让"测试依赖了哪个编码规则"一眼可见。
     */
    private static final class SparseEncoderAccess {
        static SparseVector encode(String text) {
            return com.shiyi.domain.agent.service.knowledge.impl.SparseEncoder.encode(text);
        }

        static SparseVector encodeQuery(String text) {
            return com.shiyi.domain.agent.service.knowledge.impl.SparseEncoder.encodeQuery(text);
        }
    }
}