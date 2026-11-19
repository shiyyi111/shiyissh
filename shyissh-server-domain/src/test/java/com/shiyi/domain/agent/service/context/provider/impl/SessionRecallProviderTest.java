package com.shiyi.domain.agent.service.context.provider.impl;

import com.shiyi.domain.agent.service.chat.SummaryVectorService;
import com.shiyi.domain.agent.model.valobj.enums.ContextPlacement;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SessionRecallProvider} 的渲染与排除语义单测。
 * <p>
 * 关键约束：① 只在用户消息非空时才召回；② 当前会话的摘要不能被带进来（会与窗口内容重复）；
 * ③ 渲染时显式标注"另一次会话"，避免模型把别人的结论当成本会话事实。
 *
 * @author shiyi
 */
public class SessionRecallProviderTest {

    private final SummaryVectorService summaryVectorService = mock(SummaryVectorService.class);
    private final SessionRecallProvider provider = new SessionRecallProvider();

    public SessionRecallProviderTest() {
        setField(provider, "summaryVectorService", summaryVectorService);
    }

    @Test
    public void shouldRenderRecalledSummariesAsOtherSessions() {
        when(summaryVectorService.recall("u-1", "s-current", "上次 502 怎么修的？", 2))
                .thenReturn(List.of(
                        new SummaryVectorService.SummaryRecall("s-a", "连接池打满，已扩容", 0.7),
                        new SummaryVectorService.SummaryRecall("s-b", "证书过期，已续期", 0.5)));

        Map<String, Object> ctx = provider.provide("s-current", "u-1", null, "",
                List.of(user("上次 502 怎么修的？")));

        String text = String.valueOf(ctx.get("sessionRecallSummary"));
        assertTrue(text.contains("（另一次会话）连接池打满，已扩容"));
        assertTrue(text.contains("（另一次会话）证书过期，已续期"));
        assertTrue("两条之间要有换行", text.contains("\n"));
    }

    @Test
    public void emptyRecallShouldNotPutKey() {
        when(summaryVectorService.recall("u-1", "s-current", "你好", 2)).thenReturn(List.of());

        Map<String, Object> ctx = provider.provide("s-current", "u-1", null, "",
                List.of(user("你好")));

        assertFalse(ctx.containsKey("sessionRecallSummary"));
    }

    @Test
    public void blankUserOrQueryShouldSkipRecallEntirely() {
        provider.provide("s-current", "", null, "", List.of(user("问题")));
        provider.provide("s-current", "u-1", null, "", List.of());

        verify(summaryVectorService, never()).recall(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    public void shouldDeclareEphemeralPlacementAndOrderAfterMemoryRecall() {
        assertEquals(ContextPlacement.EPHEMERAL_SUFFIX, provider.getPlacement());
        assertTrue("要排在长期记忆动态召回（26）之后", provider.getOrder() > 26);
        assertTrue(provider.enabled());
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> user(String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", content);
        return message;
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