package com.shiyi.domain.agent.service.memory;

import com.shiyi.domain.agent.adapter.embedding.EmbeddingClient;
import com.shiyi.domain.agent.adapter.repository.ILongTermMemoryRepository;
import com.shiyi.domain.agent.adapter.vector.SparseVector;
import com.shiyi.domain.agent.adapter.vector.VectorStore;
import com.shiyi.domain.agent.model.entity.LongTermMemoryEntity;
import com.shiyi.domain.agent.model.valobj.properties.RagProperties;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MemoryVectorService} 单测。
 * <p>
 * 只测"编排是否正确"：谁被调用、payload 写了什么、失败时状态怎么落。
 * 真实检索质量由 app 模块的语义召回评测覆盖——那里才有真 embedding 与真 Qdrant。
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class MemoryVectorServiceTest {

    @Mock
    private ILongTermMemoryRepository memoryRepository;

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private VectorStore vectorStore;

    private MemoryVectorService service;

    private void init() throws Exception {
        service = new MemoryVectorService();
        setField(service, "longTermMemoryRepository", memoryRepository);
        setField(service, "embeddingClientProvider", provider(embeddingClient));
        setField(service, "vectorStoreProvider", provider(vectorStore));
        RagProperties properties = new RagProperties();
        properties.getVector().setEnabled(true);
        properties.getVector().setCollectionPrefix("shiyissh");
        properties.getEmbedding().setEnabled(true);
        properties.getEmbedding().setDimensions(1024);
        setField(service, "ragProperties", properties);
    }

    private static LongTermMemoryEntity memory(long id, String scope, String hostKey, String type, String content) {
        return LongTermMemoryEntity.builder()
                .id(id)
                .userId("u-1")
                .scope(scope)
                .hostKey(hostKey)
                .memoryType(type)
                .content(content)
                .keywords("nginx,502")
                .embeddingStatus(0)
                .embeddingAttempts(0)
                .build();
    }

    @Test
    public void indexPendingShouldWriteHybridPointsAndMarkEmbedded() throws Exception {
        init();
        LongTermMemoryEntity first = memory(7L, "HOST", "host-2", "TROUBLESHOOTING_CASE", "nginx 502 是上游连接池耗尽");
        LongTermMemoryEntity second = memory(8L, "USER", "", "USER_PREFERENCE", "习惯用 vim 编辑配置");
        when(memoryRepository.findPendingEmbedding(anyInt())).thenReturn(List.of(first, second));
        when(embeddingClient.available()).thenReturn(true);
        when(embeddingClient.embedBatch(anyList())).thenReturn(List.of(new float[]{0.1f}, new float[]{0.2f}));

        int indexed = service.indexPending(50);

        assertEquals(2, indexed);
        assertEquals("shiyissh_memory_hybrid_1024", service.collectionName());
        verify(vectorStore).createHybridCollectionIfAbsent("shiyissh_memory_hybrid_1024", 1024);
        ArgumentCaptor<List<VectorStore.HybridPoint>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).upsertHybrid(eq("shiyissh_memory_hybrid_1024"), captor.capture());
        List<VectorStore.HybridPoint> points = captor.getValue();
        assertEquals(2, points.size());
        assertEquals("mem:7", points.get(0).id());
        assertEquals("host-2", points.get(0).payload().get("host_key"));
        assertEquals("HOST", points.get(0).payload().get("scope"));
        assertEquals("u-1", points.get(0).payload().get("user_id"));
        assertFalse(points.get(0).sparse().isEmpty());
        verify(memoryRepository, times(2)).markEmbedded(any());
        verify(memoryRepository, never()).markEmbeddingFailed(any());
    }

    @Test
    public void embeddingFailureShouldMarkFailedWithoutWriting() throws Exception {
        init();
        when(memoryRepository.findPendingEmbedding(anyInt())).thenReturn(List.of(memory(9L, "HOST", "h", "SOFTWARE_FACT", "redis 版本 6.2")));
        when(embeddingClient.available()).thenReturn(true);
        when(embeddingClient.embedBatch(anyList())).thenReturn(null);

        int indexed = service.indexPending(50);

        assertEquals(0, indexed);
        verify(memoryRepository).markEmbeddingFailed(9L);
        verify(vectorStore, never()).upsertHybrid(anyString(), anyList());
    }

    @Test
    public void recallShouldNormalizeScoresAndFilterByOwnership() throws Exception {
        init();
        when(embeddingClient.available()).thenReturn(true);
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.3f});
        List<VectorStore.Hit> hits = new ArrayList<>();
        hits.add(new VectorStore.Hit("mem:11", 0.8d, Map.of("id", "11", "scope", "USER")));
        hits.add(new VectorStore.Hit("mem:12", 0.4d, Map.of("id", "12", "scope", "HOST", "host_key", "host-2")));
        when(vectorStore.queryHybrid(anyString(), any(), any(SparseVector.class), anyInt(), any(), anyList()))
                .thenReturn(hits);

        Map<Long, Double> scores = service.recallScores("u-1", "host-2", "网关一直返回 502", 50);

        assertEquals(2, scores.size());
        assertEquals(1.0d, scores.get(11L), 1e-9);
        assertEquals(0.5d, scores.get(12L), 1e-9);
        ArgumentCaptor<Map<String, String>> mustCaptor = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> shouldCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).queryHybrid(eq("shiyissh_memory_hybrid_1024"), any(), any(SparseVector.class),
                eq(50), mustCaptor.capture(), shouldCaptor.capture());
        assertEquals("u-1", mustCaptor.getValue().get("user_id"));
        assertTrue(shouldCaptor.getValue().contains(Map.of("scope", "USER")));
        assertTrue(shouldCaptor.getValue().contains(Map.of("host_key", "host-2")));
    }

    @Test
    public void recallFailureShouldReturnEmptyInsteadOfThrowing() throws Exception {
        init();
        when(embeddingClient.available()).thenReturn(true);
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.3f});
        when(vectorStore.queryHybrid(anyString(), any(), any(SparseVector.class), anyInt(), any(), anyList()))
                .thenThrow(new IllegalStateException("connection refused"));

        assertTrue(service.recallScores("u-1", "host-2", "磁盘满", 50).isEmpty());
    }

    // ==================== 测试脚手架 ====================

    /**
     * 用 {@link java.util.Optional} 模拟 ObjectProvider：Mockito 直接 mock 接口也行，
     * 但 ObjectProvider 的方法较多，用一个只实现 {@code getIfAvailable} 的匿名实现更省事。
     */
    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T instance) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }

            @Override
            public T getObject() {
                return instance;
            }
        };
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}