package com.shiyi.domain.agent.service.chat;

import com.shiyi.domain.agent.adapter.embedding.EmbeddingClient;
import com.shiyi.domain.agent.adapter.vector.VectorStore;
import com.shiyi.domain.agent.model.valobj.properties.RagProperties;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SummaryVectorService} 的索引与召回语义单测。
 * <p>
 * 守住两条：① 写索引是 best-effort，失败不许冒泡到对话链路；
 * ② 召回必须排除当前会话（它的上下文已经在窗口里，重复注入只会浪费 token）。
 *
 * @author shiyi
 */
public class SummaryVectorServiceTest {

    private RagProperties ragProperties;
    private EmbeddingClient embeddingClient;
    private VectorStore vectorStore;
    private SummaryVectorService service;

    @Before
    public void setUp() {
        ragProperties = new RagProperties();
        ragProperties.getVector().setEnabled(true);
        ragProperties.getVector().setCollectionPrefix("shiyissh");
        ragProperties.getEmbedding().setDimensions(1024);

        embeddingClient = mock(EmbeddingClient.class);
        vectorStore = mock(VectorStore.class);
        when(embeddingClient.available()).thenReturn(true);
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});

        service = new SummaryVectorService(provider(embeddingClient), provider(vectorStore));
        setField(service, "ragProperties", ragProperties);
    }

    @Test
    public void indexShouldUpsertSummaryWithUserAndSessionPayload() {
        service.index("s-1", "u-1", "上次 nginx 502 的根因是上游连接池打满，已扩容到 200");

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<VectorStore.HybridPoint>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(vectorStore).createHybridCollectionIfAbsent(eq("shiyissh_summary_hybrid_1024"), eq(1024));
        verify(vectorStore).upsertHybrid(eq("shiyissh_summary_hybrid_1024"), captor.capture());
        VectorStore.HybridPoint point = captor.getValue().get(0);
        assertEquals("sum:s-1", point.id());
        assertEquals(2, point.dense().length);
        assertTrue("稀疏向量不应为空", point.sparse() != null && !point.sparse().isEmpty());
        assertEquals("u-1", point.payload().get("user_id"));
        assertEquals("s-1", point.payload().get("session_id"));
        assertEquals("摘要原文要进 payload，召回时直接渲染",
                "上次 nginx 502 的根因是上游连接池打满，已扩容到 200", point.payload().get("summary"));
    }

    @Test
    public void recallShouldSkipCurrentSessionAndKeepOrder() {
        when(vectorStore.queryHybrid(eq("shiyissh_summary_hybrid_1024"), any(), any(), anyInt(),
                anyMap(), isNull())).thenReturn(List.of(
                hit("s-current", "当前会话的摘要", 0.9),
                hit("s-old-1", "更早一次会话的结论 A", 0.7),
                hit("s-old-2", "更早一次会话的结论 B", 0.5)));

        List<SummaryVectorService.SummaryRecall> recalls =
                service.recall("u-1", "s-current", "上次 502 怎么修的", 2);

        assertEquals("当前会话必须被排除", 2, recalls.size());
        assertEquals("s-old-1", recalls.get(0).sessionId());
        assertEquals("更早一次会话的结论 A", recalls.get(0).summary());
        assertEquals("s-old-2", recalls.get(1).sessionId());
        verify(vectorStore).queryHybrid(eq("shiyissh_summary_hybrid_1024"), any(), any(), anyInt(),
                eq(Map.of("user_id", "u-1")), isNull());
    }

    @Test
    public void indexFailureShouldNotPropagate() {
        doThrow(new IllegalStateException("qdrant down")).when(vectorStore)
                .upsertHybrid(anyString(), anyList());

        service.index("s-1", "u-1", "随便一条摘要");

        // 不抛异常即通过；集合已建好就不必重复建（collectionReady 只认建集合成功，与写点失败无关）
        service.index("s-2", "u-1", "第二条摘要");
        verify(vectorStore, org.mockito.Mockito.times(2)).upsertHybrid(anyString(), anyList());
        verify(vectorStore, org.mockito.Mockito.times(1))
                .createHybridCollectionIfAbsent(eq("shiyissh_summary_hybrid_1024"), eq(1024));
    }

    @Test
    public void unavailableEmbeddingShouldSkipIndexAndRecall() {
        when(embeddingClient.available()).thenReturn(false);

        service.index("s-1", "u-1", "摘要");
        assertTrue(service.recall("u-1", "s-2", "问题", 2).isEmpty());

        verify(vectorStore, never()).upsertHybrid(anyString(), anyList());
        verify(vectorStore, never()).queryHybrid(anyString(), any(), any(), anyInt(), anyMap(), any());
    }

    @Test
    public void blankInputShouldBeIgnored() {
        service.index(null, "u-1", "摘要");
        service.index("s-1", "u-1", " ");
        assertTrue(service.recall("", "s-2", "问题", 2).isEmpty());
        assertTrue(service.recall("u-1", "s-2", " ", 2).isEmpty());

        verify(vectorStore, never()).upsertHybrid(anyString(), anyList());
    }

    // ------------------------------------------------------------------ helpers

    private static VectorStore.Hit hit(String sessionId, String summary, double score) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", sessionId);
        payload.put("summary", summary);
        return new VectorStore.Hit("sum:" + sessionId, score, payload);
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
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
}