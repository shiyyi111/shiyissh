package com.shiyi.domain.agent.service.knowledge.impl;

import com.shiyi.domain.agent.adapter.embedding.EmbeddingClient;
import com.shiyi.domain.agent.adapter.repository.IWikiRepository;
import com.shiyi.domain.agent.adapter.vector.SparseVector;
import com.shiyi.domain.agent.adapter.vector.VectorStore;
import com.shiyi.domain.agent.model.entity.WikiEntryEntity;
import com.shiyi.domain.agent.model.valobj.properties.RagProperties;
import com.shiyi.domain.agent.service.knowledge.KnowledgeRetriever;
import com.shiyi.domain.agent.service.knowledge.LlmQueryRewriter;
import com.shiyi.domain.agent.service.knowledge.QueryNormalizer;
import com.shiyi.domain.agent.service.knowledge.RerankService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索器 P1（混合检索重写）的回归测试。
 * <p>
 * 覆盖四件事，都是"换实现时最容易悄悄丢掉"的契约：
 * <ol>
 *   <li>命中里返回的 {@code docId} 必须是业务编号（KB-001），不是自增主键；</li>
 *   <li>回表必须带 userId/hostKey，否则换个检索路径就能绕过归属隔离；</li>
 *   <li>wiki 命中要带 {@code wiki:} 前缀，且按 payload 的 {@code source} 分流回表；</li>
 *   <li>向量库不可用时必须 {@code degraded=true} 且返回空，而不是抛异常（fail-open）；</li>
 *   <li>重排失败必须回退到召回顺序，不能把结果吞成空。</li>
 * </ol>
 * 领域模块的测试类路径里没有 spring-test，所以 {@code @ConfigurationProperties} 与
 * {@code ObjectProvider} 都用反射手工注入。
 *
 * @author shiyi
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class HybridKnowledgeRetrieverTest {

    @Mock
    private QueryNormalizer queryNormalizer;

    @Mock
    private IWikiRepository wikiRepository;

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private KnowledgeIndexer knowledgeIndexer;

    @Mock
    private LlmQueryRewriter llmQueryRewriter;

    @Mock
    private ObjectProvider<VectorStore> vectorStoreProvider;

    @Mock
    private ObjectProvider<RerankService> rerankServiceProvider;

    @InjectMocks
    private HybridKnowledgeRetriever retriever = new HybridKnowledgeRetriever();

    private VectorStore vectorStore;

    private void setUp(boolean vectorEnabled) {
        RagProperties ragProperties = new RagProperties();
        ragProperties.getVector().setEnabled(vectorEnabled);
        setField(retriever, "ragProperties", ragProperties);
        setField(retriever, "llmQueryRewriter", llmQueryRewriter);
        when(queryNormalizer.normalize(anyString()))
                .thenReturn(new QueryNormalizer.Normalized("磁盘", "磁盘", List.of("磁盘"), List.of()));
        when(llmQueryRewriter.available()).thenReturn(false);
        when(knowledgeIndexer.hybridCollectionName()).thenReturn("shiyissh_knowledge_hybrid_1024");
        // 默认无重排实现：走本地覆盖率重排，避免单测被外呼影响
        when(rerankServiceProvider.getIfAvailable()).thenReturn(null);
        vectorStore = mock(VectorStore.class);
        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(embeddingClient.available()).thenReturn(vectorEnabled);
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f, 0.3f, 0.4f});
    }

    /** 领域模块的测试类路径里没有 spring-test，用反射直接注入字段。 */
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
     * md 静态知识的 payload：正文直接在里面（去库之后没有回表这一跳）。
     */
    private static Map<String, Object> mdPayload(String docId, String title) {
        return Map.of("id", docId, "doc_id", docId,
                "title", title,
                "kind", "runbook",
                "content", title + " 的排查步骤：df -h 看磁盘占用",
                "commands", "df -h",
                "tags", "磁盘,存储",
                "source", "md",
                "scope", "GLOBAL");
    }

    private void stubQueryHits(VectorStore.Hit... hits) {
        // dense 用 any() 而不是 any(float[].class)：embedding 不可用时这里传的是 null，
        // any(Class) 不匹配 null，会让"仅稀疏"用例拿到 mock 默认空列表
        when(vectorStore.queryHybrid(anyString(), any(), any(), anyInt(), anyMap()))
                .thenReturn(List.of(hits));
    }

    @Test
    public void hitShouldCarryBusinessDocIdInsteadOfPrimaryKey() {
        setUp(true);
        stubQueryHits(new VectorStore.Hit("point-1", 0.9d, mdPayload("KB-001", "磁盘空间不足")));

        KnowledgeRetriever.Result result = retriever.retrieve("default", "", "磁盘", 5);

        assertFalse("应当有命中", result.isEmpty());
        assertEquals("命中应返回业务编号 KB-001，而不是主键 1", "KB-001", result.hits().get(0).docId());
        assertFalse("向量路正常时不应标记降级", result.degraded());
    }

    @Test
    public void backfillQueryShouldCarryVisibility() {
        setUp(true);
        stubQueryHits(new VectorStore.Hit("point-7", 0.9d, Map.of("id", 7L, "source", "wiki")));
        WikiEntryEntity wiki = new WikiEntryEntity();
        wiki.setId(7L);
        wiki.setTitle("生产库磁盘告警处置");
        wiki.setContent("磁盘满了先看 df -h，再清理 /var/log");
        wiki.setTags("磁盘,告警");
        wiki.setSource("agent");
        when(wikiRepository.queryByIds(anyList(), anyString(), anyString())).thenReturn(List.of(wiki));

        KnowledgeRetriever.Result result = retriever.retrieve("user-7", "host-3", "磁盘", 5);

        assertFalse("wiki 回表应当召回", result.isEmpty());
        // 关键断言：wiki 回表必须带归属参数，否则向量路就是权限旁路。
        // md 静态知识是全局可见的，不回表，因此这里只校验 wiki 这条链路。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(wikiRepository).queryByIds(idsCaptor.capture(), eq("user-7"), eq("host-3"));
        assertEquals(List.of(7L), idsCaptor.getValue());
    }

    @Test
    public void wikiHitShouldCarryWikiPrefix() {
        setUp(true);
        stubQueryHits(new VectorStore.Hit("point-7", 0.8d, Map.of("id", 7L, "source", "wiki")));
        WikiEntryEntity wiki = new WikiEntryEntity();
        wiki.setId(7L);
        wiki.setTitle("生产库磁盘告警处置");
        wiki.setContent("磁盘满了先看 df -h，再清理 /var/log");
        wiki.setTags("磁盘,告警");
        wiki.setSource("agent");
        when(wikiRepository.queryByIds(anyList(), anyString(), anyString())).thenReturn(List.of(wiki));

        KnowledgeRetriever.Result result = retriever.retrieve("default", "", "磁盘", 5);

        assertFalse("wiki 命中不应被丢弃", result.isEmpty());
        assertEquals("wiki:7", result.hits().get(0).docId());
        assertTrue("wiki 的 kind 固定为 wiki", "wiki".equals(result.hits().get(0).kind()));
    }

    @Test
    public void embeddingUnavailableShouldStillAnswerFromSparseRoute() {
        setUp(true);
        // embedding 外呼失败（欠费/超时）时客户端返回空向量：检索不该整条失败，
        // 稀疏路是纯本地计算，能查就必须查——否则一次欠费等于知识库全停
        when(embeddingClient.available()).thenReturn(false);
        stubQueryHits(new VectorStore.Hit("point-1", 0.9d, mdPayload("KB-001", "磁盘空间不足")));

        KnowledgeRetriever.Result result = retriever.retrieve("default", "", "磁盘", 5);

        assertEquals("仅稀疏也必须能召回", "KB-001", result.hits().get(0).docId());
        assertFalse("稀疏路可用时不算降级（只是丢了同义改写能力）", result.degraded());
        verify(vectorStore).queryHybrid(anyString(), isNull(), any(SparseVector.class), anyInt(), anyMap());
    }

    @Test
    public void queryFailureShouldDegradeInsteadOfThrow() {
        setUp(true);
        when(vectorStore.queryHybrid(anyString(), any(), any(), anyInt(), anyMap()))
                .thenThrow(new IllegalStateException("connection refused"));

        KnowledgeRetriever.Result result = retriever.retrieve("default", "", "磁盘", 5);

        assertTrue("向量库整条不可用必须显式标记降级", result.degraded());
        assertTrue("降级时返回空结果，由上层决定怎么回答", result.hits().isEmpty());
    }

    @Test
    public void rerankFailureShouldFallBackToRecallOrder() {
        setUp(true);
        stubQueryHits(
                new VectorStore.Hit("point-1", 0.9d, mdPayload("KB-001", "磁盘空间不足")),
                new VectorStore.Hit("point-2", 0.8d, mdPayload("KB-002", "磁盘 inode 耗尽")));
        RerankService broken = mock(RerankService.class);
        when(broken.available()).thenReturn(true);
        when(broken.rerank(anyString(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("HTTP 429"));
        when(rerankServiceProvider.getIfAvailable()).thenReturn(broken);

        KnowledgeRetriever.Result result = retriever.retrieve("default", "", "磁盘", 5);

        assertEquals("重排炸了也必须保留召回结果", 2, result.hits().size());
        assertFalse(result.degraded());
    }

    /** 给检索器装上可用的 BM25 腿：单测不启动 Spring，用反射注入。 */
    private Bm25KnowledgeIndex installBm25() {
        Bm25KnowledgeIndex bm25Index = mock(Bm25KnowledgeIndex.class);
        when(bm25Index.available()).thenReturn(true);
        setField(retriever, "bm25Index", bm25Index);
        return bm25Index;
    }

    @Test
    public void bm25LegShouldFuseWithDenseRouteThroughRrf() {
        setUp(true);
        stubQueryHits(new VectorStore.Hit("point-1", 0.9d, mdPayload("KB-001", "磁盘空间不足")));
        Bm25KnowledgeIndex bm25Index = installBm25();
        when(bm25Index.search(anyString(), anyInt())).thenReturn(List.of(
                new Bm25KnowledgeIndex.Doc("KB-002", "磁盘 inode 耗尽", "inode 满了也要 df -i",
                        "df -i", "磁盘", "runbook", "md", 0d)));

        KnowledgeRetriever.Result result = retriever.retrieve("default", "", "磁盘", 5);

        assertEquals("稠密 + 关键词两路都命中的都要留下", 2, result.hits().size());
        assertTrue("稠密路命中在", result.hits().stream().anyMatch(h -> "KB-001".equals(h.docId())));
        assertTrue("BM25 路命中在", result.hits().stream().anyMatch(h -> "KB-002".equals(h.docId())));
        assertFalse("两路都正常时不算降级", result.degraded());
        // 关键词腿接管后稠密路只传 dense，不再往 Qdrant 传稀疏向量
        verify(vectorStore).queryHybrid(anyString(), any(), isNull(), anyInt(), anyMap());
    }

    @Test
    public void bm25ShouldStillAnswerWhenEmbeddingUnavailable() {
        setUp(true);
        when(embeddingClient.available()).thenReturn(false);
        Bm25KnowledgeIndex bm25Index = installBm25();
        when(bm25Index.search(anyString(), anyInt())).thenReturn(List.of(
                new Bm25KnowledgeIndex.Doc("KB-002", "磁盘 inode 耗尽", "inode 满了也要 df -i",
                        "df -i", "磁盘", "runbook", "md", 0d)));

        KnowledgeRetriever.Result result = retriever.retrieve("default", "", "磁盘", 5);

        assertEquals("embedding 挂掉时关键词腿必须兜住", "KB-002", result.hits().get(0).docId());
        assertFalse("关键词腿可用时不算降级", result.degraded());
        // 稠密不可用就不该查 Qdrant：少一次外呼，也避免拿到无意义的最近邻
        verify(vectorStore, never()).queryHybrid(anyString(), any(), any(), anyInt(), anyMap());
    }
}