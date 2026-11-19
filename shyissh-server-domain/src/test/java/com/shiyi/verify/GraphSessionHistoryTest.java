package com.shiyi.verify;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.shiyi.domain.agent.graph.GraphStateKeys;
import com.shiyi.domain.agent.graph.SshAgentGraph;
import com.shiyi.domain.agent.graph.support.GraphSession;
import com.shiyi.domain.agent.model.entity.ChatMessageEntity;
import com.shiyi.domain.agent.service.IChatContextService;
import com.shiyi.domain.agent.service.ILongTermMemoryService;
import com.shiyi.domain.agent.service.chat.GraphChatService;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话历史接线验证（P3 第一块）。
 * <p>
 * 对应老版的 {@code RootNode} 冷启动恢复：如果这里没接上，每一轮对话都是"失忆"的，
 * 用户说"现在呢"模型根本不知道在问什么。本测试保证两件事：
 * <ol>
 *   <li>{@link GraphSession#loadHistory} 能把 DB 里的 user/assistant 正确转成 Spring AI 消息，
 *       并跳过 tool/system（跨请求重放容易错位）；</li>
 *   <li>{@link GraphChatService#handleMessageStream} 真的把历史拼进了图的初始 MESSAGES，
 *       而不是只放一条 {@code new UserMessage(message)}。</li>
 * </ol>
 *
 * @author shiyi
 */
public class GraphSessionHistoryTest {

    @Test
    public void shouldLoadUserAndAssistantAndSkipTool() {
        ILongTermMemoryService memoryService = mock(ILongTermMemoryService.class);
        when(memoryService.getRecentMessages("s1", GraphSession.DEFAULT_HISTORY_LIMIT)).thenReturn(List.of(
                entity("user", "看看这台机器磁盘还剩多少空间"),
                entity("assistant", "磁盘使用率 48%"),
                entity("tool", "/dev/vda1 40G 18G 20G 48% /"),
                entity("system", "系统提示")));

        List<Message> history = GraphSession.loadHistory(memoryService, "s1");

        assertEquals("只应保留 user + assistant 两条", 2, history.size());
        assertTrue("第 1 条应为用户消息", history.get(0) instanceof UserMessage);
        assertEquals("看看这台机器磁盘还剩多少空间", history.get(0).getText());
        assertTrue("第 2 条应为助手消息", history.get(1) instanceof AssistantMessage);
        assertEquals("磁盘使用率 48%", history.get(1).getText());
    }

    @Test
    public void shouldReturnEmptyWhenServiceOrSessionMissing() {
        assertTrue(GraphSession.loadHistory(null, "s1").isEmpty());
        assertTrue(GraphSession.loadHistory(mock(ILongTermMemoryService.class), "  ").isEmpty());
    }

    @Test
    public void shouldCapHistoryAtTwelveRecentTurns() {
        ILongTermMemoryService memoryService = mock(ILongTermMemoryService.class);
        List<ChatMessageEntity> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(entity(i % 2 == 0 ? "user" : "assistant", "m" + i));
        }
        when(memoryService.getRecentMessages(any(), eq(GraphSession.DEFAULT_HISTORY_LIMIT))).thenReturn(many);

        List<Message> history = GraphSession.loadHistory(memoryService, "s1");

        // 25 条里 13 个 user（m0/m2/…/m24）。轮数闸限 12 轮 → 从第 12 个 user（m2）开始 = 23 条。
        // 这些消息都很短，token 闸（6000）不会先触发，所以能看出轮数闸的真实边界。
        assertEquals("应只保留最近 12 轮", 23, history.size());
        assertTrue("窗口第一条必须是 user", history.get(0) instanceof UserMessage);
        assertEquals("m2", history.get(0).getText());
        assertEquals("应保留最近的 m24", "m24", history.get(history.size() - 1).getText());
    }

    @Test
    public void shouldTrimWholeTurnsWhenOverTokenBudget() {
        ILongTermMemoryService memoryService = mock(ILongTermMemoryService.class);
        String longAnswer = "x".repeat(10000);  // 约 2500 token（ASCII 四字符一 token）
        List<ChatMessageEntity> many = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            many.add(entity("user", "第" + i + "轮提问"));
            many.add(entity("assistant", longAnswer));
        }
        when(memoryService.getRecentMessages(any(), eq(GraphSession.DEFAULT_HISTORY_LIMIT))).thenReturn(many);

        List<Message> history = GraphSession.loadHistory(memoryService, "s1");

        // 每轮约 2513 token，6000 预算只能放 2 轮（4 条），且必须整轮丢弃
        assertEquals("超预算应整轮丢弃，保留 2 轮", 4, history.size());
        assertTrue("切点仍在 user 上", history.get(0) instanceof UserMessage);
        assertEquals("应保留最近的提问", "第4轮提问", history.get(2).getText());
    }

    @Test
    public void shouldKeepLastTurnEvenIfItAloneExceedsBudget() {
        ILongTermMemoryService memoryService = mock(ILongTermMemoryService.class);
        when(memoryService.getRecentMessages(any(), eq(GraphSession.DEFAULT_HISTORY_LIMIT))).thenReturn(List.of(
                entity("user", "看下这个大日志"),
                entity("assistant", "y".repeat(20000))));
        List<Message> history = GraphSession.loadHistory(memoryService, "s1");

        // 失忆比超长更糟：单轮超预算时仍保留这一轮
        assertEquals(2, history.size());
        assertTrue(history.get(0) instanceof UserMessage);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void graphChatServiceShouldInjectHistoryIntoTMessages() {
        ILongTermMemoryService memoryService = mock(ILongTermMemoryService.class);
        when(memoryService.getRecentMessages("s1", GraphSession.DEFAULT_HISTORY_LIMIT)).thenReturn(List.of(
                entity("user", "看看这台机器磁盘还剩多少空间"),
                entity("assistant", "磁盘使用率 48%")));

        SshAgentGraph graph = mock(SshAgentGraph.class);
        when(graph.stream(any(), any())).thenReturn(Flux.<NodeOutput>empty());

        GraphChatService service = new GraphChatService();
        inject(service, "sshAgentGraph", graph);
        inject(service, "longTermMemoryService", memoryService);

        service.handleMessageStream("u1", "s1", "现在呢", null);

        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(graph).stream(inputCaptor.capture(), any(RunnableConfig.class));
        List<Message> messages = (List<Message>) inputCaptor.getValue().get(GraphStateKeys.MESSAGES);
        assertNotNull(messages);
        assertEquals("历史 2 条 + 本次用户消息 1 条", 3, messages.size());
        assertEquals("看看这台机器磁盘还剩多少空间", messages.get(0).getText());
        assertEquals("磁盘使用率 48%", messages.get(1).getText());
        assertEquals("现在呢", messages.get(2).getText());
    }

    /**
     * 权重压缩必须在硬闸之前生效：reducer 挑剩的才轮到 4 轮/6000 token/20 条。
     * <p>
     * 这里 mock 掉 {@code trimHistory} 只留最后两条，验证 loadHistory 真的把它当第一道闸
     * （此前 {@code ChatContextService.trimHistory} 在图链路是零调用——reducer 三件套白写了）。
     */
    @Test
    public void shouldApplyHybridReducerBeforeHardCaps() {
        ILongTermMemoryService memoryService = mock(ILongTermMemoryService.class);
        when(memoryService.getRecentMessages("s9", GraphSession.DEFAULT_HISTORY_LIMIT)).thenReturn(List.of(
                entity("user", "第一轮问题"), entity("assistant", "第一轮回答"),
                entity("user", "第二轮问题"), entity("assistant", "第二轮回答")));

        IChatContextService contextService = mock(IChatContextService.class);
        when(contextService.trimHistory(anyList(), eq(GraphSession.DEFAULT_HISTORY_TOKEN_BUDGET)))
                .thenAnswer(invocation -> {
                    List<Map<String, Object>> maps = invocation.getArgument(0);
                    return new ArrayList<>(maps.subList(maps.size() - 2, maps.size()));
                });

        List<Message> history = GraphSession.loadHistory(memoryService, contextService, "s9");

        assertEquals("reducer 只留两条，硬闸不该再补回来", 2, history.size());
        assertEquals("第二轮问题", history.get(0).getText());
        assertEquals("第二轮回答", history.get(1).getText());
        verify(contextService).trimHistory(anyList(), eq(GraphSession.DEFAULT_HISTORY_TOKEN_BUDGET));
    }

    /**
     * 摘要压缩的前提：装载时必须能说清"哪些被丢了"。
     * <p>
     * 三道闸只做"丢"不做"留"，如果 {@code loadHistoryDetailed} 不返回 {@code dropped}，
     * 摘要器就拿不到料，历史一超预算就等于失忆。这里验证 dropped 正好是保留窗口之前的前缀。
     */
    @Test
    public void shouldReportDroppedPrefixForSummarizer() {
        ILongTermMemoryService memoryService = mock(ILongTermMemoryService.class);
        List<ChatMessageEntity> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(entity(i % 2 == 0 ? "user" : "assistant", "m" + i));
        }
        when(memoryService.getRecentMessages(any(), eq(GraphSession.DEFAULT_HISTORY_LIMIT))).thenReturn(many);

        GraphSession.LoadedHistory loaded = GraphSession.loadHistoryDetailed(memoryService, null, "s1");

        assertEquals("保留窗口是最近 12 轮 = 23 条", 23, loaded.messages().size());
        assertEquals("其余 2 条都应作为 dropped 交给摘要器", 2, loaded.dropped().size());
        assertEquals("dropped 从最早那条开始", "m0", loaded.dropped().get(0).getText());
        assertEquals("dropped 的最后一条紧邻保留窗口", "m1", loaded.dropped().get(1).getText());
        assertEquals("保留窗口第一条必须是 user", "m2", loaded.messages().get(0).getText());
    }

    /**
     * 闸④：即使三道硬闸一次都没裁（token/轮数/条数都在范围内），首条也必须是 user。
     * <p>
     * 候选池是"最新 100 行"，池首落在哪条记录上取决于历史长度，完全可能是 assistant 或
     * 命令痕迹。以前三道闸在"不需要裁"时直接原样返回、不做任何对齐，这种畸形序列会照原样
     * 拼进 prompt（system 之后第一条是 assistant），部分模型会直接报错。
     */
    @Test
    public void shouldDropLeadingAssistantEvenWhenNoGateTrims() {
        ILongTermMemoryService memoryService = mock(ILongTermMemoryService.class);
        when(memoryService.getRecentMessages("s3", GraphSession.DEFAULT_HISTORY_LIMIT)).thenReturn(List.of(
                entity("assistant", "曾执行命令：df -h"),
                entity("user", "接着看看内存"),
                entity("assistant", "内存使用 62%")));

        GraphSession.LoadedHistory loaded = GraphSession.loadHistoryDetailed(memoryService, null, "s3");

        assertEquals("首条 assistant 必须被丢掉", 2, loaded.messages().size());
        assertTrue("保留窗口第一条必须是 user", loaded.messages().get(0) instanceof UserMessage);
        assertEquals("接着看看内存", loaded.messages().get(0).getText());
        assertEquals("被丢的那条要交给摘要器", 1, loaded.dropped().size());
        assertEquals("曾执行命令：df -h", loaded.dropped().get(0).getText());
    }

    /**
     * 闸④ 在 reducer 之后的场景：优先级裁剪按"单条重要度"挑，完全可能只留下一条 assistant，
     * 而其后的 user 被挑掉。此时必须继续往后丢到首条 user，而不是把 assistant 当开头。
     */
    @Test
    public void shouldAlignToUserAfterPriorityReducerKeepsAssistantHead() {
        ILongTermMemoryService memoryService = mock(ILongTermMemoryService.class);
        when(memoryService.getRecentMessages("s4", GraphSession.DEFAULT_HISTORY_LIMIT)).thenReturn(List.of(
                entity("user", "第一轮问题"), entity("assistant", "第一轮回答"),
                entity("user", "第二轮问题"), entity("assistant", "第二轮回答")));

        IChatContextService contextService = mock(IChatContextService.class);
        when(contextService.trimHistory(anyList(), eq(GraphSession.DEFAULT_HISTORY_TOKEN_BUDGET)))
                .thenAnswer(invocation -> {
                    List<Map<String, Object>> maps = invocation.getArgument(0);
                    // 模拟"重要性排序只留 assistant1 与第二轮"：首条是 assistant
                    return new ArrayList<>(List.of(maps.get(1), maps.get(2), maps.get(3)));
                });

        List<Message> history = GraphSession.loadHistory(memoryService, contextService, "s4");

        assertEquals("丢到首条 user 为止", 2, history.size());
        assertEquals("第二轮问题", history.get(0).getText());
        assertEquals("第二轮回答", history.get(1).getText());
    }

    /**
     * 闸④ 的极端分支：整段历史里一条 user 都没有（DB 里只剩 assistant/命令痕迹）。
     * 这种情况下不能硬凑一个开头，只能返回空历史，把整段交给摘要器。
     */
    @Test
    public void shouldReturnEmptyHistoryWhenNoUserSurvives() {
        ILongTermMemoryService memoryService = mock(ILongTermMemoryService.class);
        when(memoryService.getRecentMessages("s5", GraphSession.DEFAULT_HISTORY_LIMIT)).thenReturn(List.of(
                entity("user", "第一轮问题"), entity("assistant", "第一轮回答"),
                entity("user", "第二轮问题"), entity("assistant", "第二轮回答")));

        IChatContextService contextService = mock(IChatContextService.class);
        when(contextService.trimHistory(anyList(), eq(GraphSession.DEFAULT_HISTORY_TOKEN_BUDGET)))
                .thenAnswer(invocation -> {
                    List<Map<String, Object>> maps = invocation.getArgument(0);
                    // 模拟"只剩两条 assistant"：整段没有 user
                    return new ArrayList<>(List.of(maps.get(1), maps.get(3)));
                });

        GraphSession.LoadedHistory loaded = GraphSession.loadHistoryDetailed(memoryService, contextService, "s5");

        assertTrue("没有 user 就只能给空历史", loaded.messages().isEmpty());
        assertEquals("整段都要交给摘要器", 4, loaded.dropped().size());
        assertEquals("第一轮问题", loaded.dropped().get(0).getText());
        assertEquals("第二轮问题", loaded.dropped().get(2).getText());
    }

    private static ChatMessageEntity entity(String role, String content) {
        return ChatMessageEntity.builder().sessionId("s1").role(role).content(content).build();
    }

    private static void inject(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("注入字段失败: " + fieldName, e);
        }
    }
}