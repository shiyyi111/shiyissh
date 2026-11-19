package com.shiyi.domain.agent.service.chat;

import com.shiyi.domain.agent.adapter.repository.IChatSessionSummaryRepository;
import com.shiyi.domain.agent.graph.support.GraphHistorySummarizer;
import com.shiyi.domain.agent.graph.support.GraphSession;
import com.shiyi.domain.agent.model.entity.ChatSessionSummaryEntity;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SessionSummaryService} 的游标/增量/压实/并发语义单测。
 * <p>
 * 这些用例守的是"省调用"这条底线：没有新滑出时**必须** 0 次模型调用，
 * 否则每个请求都要为同一段历史重复付费；以及"拿不到摘要时不许推进游标"，
 * 否则被跳过的片段永远不会再被摘要，等于静默丢记忆。
 *
 * @author shiyi
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class SessionSummaryServiceTest {

    private static final String SESSION_ID = "s-1";
    private static final String USER_ID = "u-1";

    @Mock
    private IChatSessionSummaryRepository summaryRepository;

    @Mock
    private GraphHistorySummarizer historySummarizer;

    private SessionSummaryService service;

    @Before
    public void setUp() {
        service = new SessionSummaryService();
        setField(service, "summaryRepository", summaryRepository);
        setField(service, "historySummarizer", historySummarizer);
    }

    @Test
    public void noNewDroppedShouldReuseStoredSummaryWithoutCallingModel() {
        ChatSessionSummaryEntity stored = summary("上次的摘要", 10L, 2);
        when(summaryRepository.findBySessionId(SESSION_ID)).thenReturn(stored);

        String result = service.summarize(SESSION_ID, USER_ID, loaded(ids(5L, 10L), 10L));

        assertEquals("上次的摘要", result);
        verify(historySummarizer, never()).summarize(anyString(), anyList(), any());
    }

    @Test
    public void incrementalShouldOnlyFeedNewDroppedAndAdvanceCursor() {
        when(summaryRepository.findBySessionId(SESSION_ID)).thenReturn(summary("旧摘要", 4L, 1));
        when(historySummarizer.summarize(eq(SESSION_ID), anyList(), eq("旧摘要"))).thenReturn("合并后的摘要");
        when(summaryRepository.updateIfCursor(any(ChatSessionSummaryEntity.class), anyLong())).thenReturn(true);

        String result = service.summarize(SESSION_ID, USER_ID, loaded(ids(5L, 6L), 6L));

        assertEquals("合并后的摘要", result);
        ArgumentCaptor<List<Message>> inputCaptor = ArgumentCaptor.forClass(List.class);
        verify(historySummarizer).summarize(eq(SESSION_ID), inputCaptor.capture(), eq("旧摘要"));
        assertEquals("只应喂游标之后的两条", 2, inputCaptor.getValue().size());

        ArgumentCaptor<ChatSessionSummaryEntity> entityCaptor =
                ArgumentCaptor.forClass(ChatSessionSummaryEntity.class);
        verify(summaryRepository).updateIfCursor(entityCaptor.capture(), eq(4L));
        ChatSessionSummaryEntity saved = entityCaptor.getValue();
        assertEquals("游标必须推进到本轮最大 id", 6L, saved.getCoveredUntilId().longValue());
        assertEquals("增量计数 +1", 2, saved.getIncrements().intValue());
        assertEquals(USER_ID, saved.getUserId());
    }

    @Test
    public void longSummaryShouldTriggerCompactFromAllDropped() {
        String tooLong = "x".repeat(SessionSummaryService.MAX_SUMMARY_CHARS + 10);
        when(summaryRepository.findBySessionId(SESSION_ID)).thenReturn(summary(tooLong, 4L, 3));
        when(historySummarizer.summarize(eq(SESSION_ID), anyList(), eq(tooLong))).thenReturn("压实后的摘要");
        when(summaryRepository.updateIfCursor(any(ChatSessionSummaryEntity.class), anyLong())).thenReturn(true);

        String result = service.summarize(SESSION_ID, USER_ID, loaded(ids(5L, 6L, 7L), 7L));

        assertEquals("压实后的摘要", result);
        ArgumentCaptor<List<Message>> inputCaptor = ArgumentCaptor.forClass(List.class);
        verify(historySummarizer).summarize(eq(SESSION_ID), inputCaptor.capture(), eq(tooLong));
        assertEquals("压实要用全量被丢弃消息", 3, inputCaptor.getValue().size());
        ArgumentCaptor<ChatSessionSummaryEntity> entityCaptor =
                ArgumentCaptor.forClass(ChatSessionSummaryEntity.class);
        verify(summaryRepository).updateIfCursor(entityCaptor.capture(), eq(4L));
        assertEquals("压实后计数清零", 0, entityCaptor.getValue().getIncrements().intValue());
    }

    @Test
    public void tooManyIncrementsShouldAlsoTriggerCompact() {
        when(summaryRepository.findBySessionId(SESSION_ID))
                .thenReturn(summary("短摘要", 4L, SessionSummaryService.COMPACT_EVERY));
        when(historySummarizer.summarize(eq(SESSION_ID), anyList(), eq("短摘要"))).thenReturn("压实后的摘要");
        when(summaryRepository.updateIfCursor(any(ChatSessionSummaryEntity.class), anyLong())).thenReturn(true);

        service.summarize(SESSION_ID, USER_ID, loaded(ids(5L, 6L), 6L));

        verify(historySummarizer).summarize(eq(SESSION_ID), anyList(), eq("短摘要"));
    }

    @Test
    public void modelFailureShouldNotAdvanceCursor() {
        when(summaryRepository.findBySessionId(SESSION_ID)).thenReturn(summary("旧摘要", 4L, 1));
        when(historySummarizer.summarize(eq(SESSION_ID), anyList(), any())).thenReturn(null);

        assertNull(service.summarize(SESSION_ID, USER_ID, loaded(ids(5L, 6L), 6L)));
        verify(summaryRepository, never()).updateIfCursor(any(ChatSessionSummaryEntity.class), anyLong());
        verify(summaryRepository, never()).insertIfAbsent(any(ChatSessionSummaryEntity.class));
    }

    @Test
    public void concurrentUpdateShouldFallBackToStoredSummary() {
        when(summaryRepository.findBySessionId(SESSION_ID))
                .thenReturn(summary("旧摘要", 4L, 1))
                .thenReturn(summary("别人写的摘要", 6L, 2));
        when(historySummarizer.summarize(eq(SESSION_ID), anyList(), any())).thenReturn("我的摘要");
        when(summaryRepository.updateIfCursor(any(ChatSessionSummaryEntity.class), anyLong())).thenReturn(false);

        String result = service.summarize(SESSION_ID, USER_ID, loaded(ids(5L, 6L), 6L));

        assertEquals("并发冲突时以库里为准", "别人写的摘要", result);
    }

    @Test
    public void firstEverSummaryShouldInsertAndCountOne() {
        when(summaryRepository.findBySessionId(SESSION_ID)).thenReturn(null);
        when(historySummarizer.summarize(eq(SESSION_ID), anyList(), isNull())).thenReturn("首份摘要");
        when(summaryRepository.insertIfAbsent(any(ChatSessionSummaryEntity.class))).thenReturn(true);

        String result = service.summarize(SESSION_ID, USER_ID, loaded(ids(1L, 2L), 2L));

        assertEquals("首份摘要", result);
        ArgumentCaptor<ChatSessionSummaryEntity> entityCaptor =
                ArgumentCaptor.forClass(ChatSessionSummaryEntity.class);
        verify(summaryRepository).insertIfAbsent(entityCaptor.capture());
        assertEquals(1, entityCaptor.getValue().getIncrements().intValue());
        assertEquals(2L, entityCaptor.getValue().getCoveredUntilId().longValue());
    }

    @Test
    public void blankSessionIdOrLoadedShouldReturnEmptyWithoutTouchingStore() {
        assertEquals("", service.summarize(null, USER_ID, loaded(ids(1L), 1L)));
        assertEquals("", service.summarize(" ", USER_ID, loaded(ids(1L), 1L)));
        assertEquals("", service.summarize(SESSION_ID, USER_ID, null));
        verify(summaryRepository, never()).findBySessionId(anyString());
    }

    // ------------------------------------------------------------------ helpers

    private static ChatSessionSummaryEntity summary(String text, Long cursor, int increments) {
        ChatSessionSummaryEntity entity = new ChatSessionSummaryEntity();
        entity.setSessionId(SESSION_ID);
        entity.setUserId(USER_ID);
        entity.setSummary(text);
        entity.setCoveredUntilId(cursor);
        entity.setIncrements(increments);
        return entity;
    }

    private static List<Long> ids(Long... values) {
        return List.of(values);
    }

    private static GraphSession.LoadedHistory loaded(List<Long> ids, long maxId) {
        List<Message> messages = new java.util.ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            messages.add(new UserMessage("消息" + ids.get(i)));
        }
        return new GraphSession.LoadedHistory(List.of(), messages, ids, maxId);
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