package com.shiyi.verify;

import com.shiyi.domain.agent.model.entity.ChatMessageEntity;
import com.shiyi.domain.agent.service.ILongTermMemoryService;
import com.shiyi.domain.agent.graph.support.GraphSession;
import org.junit.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回归护栏：历史重建出来的消息里不能出现 tool 角色内容。
 * <p>
 * 约束来自 OpenAI / Spring AI 的 tool 协议：一条 assistant 消息带 {@code tool_calls} 时，
 * 后续必须有与每个 {@code tool_call_id} 一一对应的 tool 消息，否则部分模型直接 400。
 * 本项目跨请求的历史里没有 tool 消息（落库时是 tool 行，重建时被折成
 * "曾执行命令：xxx" 的助手文本），因此这里钉住三件事：
 * <ol>
 *   <li>重建结果中没有 {@link ToolResponseMessage}；</li>
 *   <li>所有 {@link AssistantMessage} 的 {@code toolCalls} 为空（没有声明却没人应答的调用）；</li>
 *   <li>被折掉命令痕迹的助手文本仍以 {@link GraphSession#COMMAND_TRACE_PREFIX} 开头，
 *       模型看得到"这些命令已经查过"，不会重复执行。</li>
 * </ol>
 *
 * @author shiyi
 */
public class GraphToolTraceFoldingTest {

    @Test
    public void shouldFoldToolMessagesIntoAssistantTracesWithoutDanglingToolCalls() {
        ILongTermMemoryService memoryService = mock(ILongTermMemoryService.class);
        List<ChatMessageEntity> entities = new ArrayList<>();
        entities.add(entity("user", "磁盘满了怎么排查"));
        entities.add(entity("assistant", "先看磁盘占用"));
        entities.add(entity("tool", "[命令] df -h\n输出：/dev/vda1 100%"));
        entities.add(entity("assistant", "根分区已满"));
        when(memoryService.getRecentMessages(anyString(), anyInt())).thenReturn(entities);

        GraphSession.LoadedHistory loaded = GraphSession.loadHistoryDetailed(memoryService, null, "s-fold-1");

        List<Message> messages = loaded.messages();
        assertEquals(4, messages.size());
        for (Message message : messages) {
            assertFalse("历史重建不应出现 tool 消息（缺 tool_call_id 无法重放）",
                    message instanceof ToolResponseMessage);
            if (message instanceof AssistantMessage assistant) {
                assertTrue("assistant 消息不应携带 toolCalls，否则就是悬挂调用",
                        assistant.getToolCalls() == null || assistant.getToolCalls().isEmpty());
            }
        }
        // tool 行被折成助手痕迹，且带命令原文
        boolean hasTrace = messages.stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .anyMatch(m -> m.getText() != null && m.getText().startsWith(GraphSession.COMMAND_TRACE_OUTPUT_PREFIX));
        assertTrue("tool 行应折成「" + GraphSession.COMMAND_TRACE_OUTPUT_PREFIX + "…」的助手文本", hasTrace);
    }

    /**
     * 近期痕迹必须带"时间 + 成败 + 一行摘要"。
     * <p>
     * 这是"模型跨轮不知道上一条命令成没成"的直接修复：只留命令时，模型会把失败当成功继续往下推，
     * 或者重复执行同一条命令。
     */
    @Test
    public void shouldCarryTimeStatusAndSummaryForRecentTrace() {
        ILongTermMemoryService memoryService = mock(ILongTermMemoryService.class);
        List<ChatMessageEntity> entities = new ArrayList<>();
        entities.add(entity("user", "看下磁盘"));
        entities.add(toolEntity("[命令] df -h\nFilesystem Size Used Avail Use% Mounted on\n/dev/vda1 40G 38G 2G 95% /"));
        when(memoryService.getRecentMessages(anyString(), anyInt())).thenReturn(entities);

        String trace = assistantTexts(GraphSession.loadHistoryDetailed(memoryService, null, "s-trace-status")).get(0);

        assertTrue("痕迹应带时间与成败：" + trace, trace.contains("，成功）"));
        assertTrue("痕迹应带输出首行：" + trace, trace.contains("Filesystem Size Used"));
        assertFalse("只取一行摘要，不应把整段输出搬进历史：" + trace, trace.contains("/dev/vda1"));
    }

    /** 失败痕迹取"错误行"而不是首行：systemctl 的首行是重定向提示，真正的错误在第二行。 */
    @Test
    public void shouldQuoteErrorLineForFailedTrace() {
        ILongTermMemoryService memoryService = mock(ILongTermMemoryService.class);
        List<ChatMessageEntity> entities = new ArrayList<>();
        entities.add(entity("user", "重启 nginx"));
        entities.add(toolEntity("[命令] systemctl restart nginx\n"
                + "Redirecting to /bin/systemctl restart nginx.service\n"
                + "Job for nginx.service failed because the control process exited with error code."));
        when(memoryService.getRecentMessages(anyString(), anyInt())).thenReturn(entities);

        String trace = assistantTexts(GraphSession.loadHistoryDetailed(memoryService, null, "s-trace-fail")).get(0);

        assertTrue("应判定为失败：" + trace, trace.contains("，失败）"));
        assertTrue("失败摘要应取含错误特征的行：" + trace, trace.contains("failed because"));
        assertFalse("不应把重定向提示当摘要：" + trace, trace.contains("Redirecting"));
    }

    /** 只有最近 5 条带摘要，更早的退化成"命令 + 时间 + 状态"，避免历史预算被输出挤爆。 */
    @Test
    public void shouldDecayOlderTracesToCommandOnly() {
        ILongTermMemoryService memoryService = mock(ILongTermMemoryService.class);
        List<ChatMessageEntity> entities = new ArrayList<>();
        entities.add(entity("user", "连续排查"));
        for (int i = 1; i <= 8; i++) {
            entities.add(toolEntity("[命令] echo " + i + "\nok-" + i));
        }
        when(memoryService.getRecentMessages(anyString(), anyInt())).thenReturn(entities);

        List<String> traces = assistantTexts(GraphSession.loadHistoryDetailed(memoryService, null, "s-trace-decay"));

        assertEquals(8, traces.size());
        for (int i = 0; i < 3; i++) {
            assertFalse("第 " + (i + 1) + " 条属于早期痕迹，不应带摘要：" + traces.get(i),
                    traces.get(i).contains("→"));
        }
        for (int i = 3; i < 8; i++) {
            assertTrue("第 " + (i + 1) + " 条属于最近 5 条，应带摘要：" + traces.get(i),
                    traces.get(i).contains("→ ok-"));
        }
    }

    private List<String> assistantTexts(GraphSession.LoadedHistory loaded) {
        return loaded.messages().stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .map(AssistantMessage::getText)
                .toList();
    }

    private ChatMessageEntity toolEntity(String content) {
        return ChatMessageEntity.builder()
                .sessionId("s-fold-1")
                .role("tool")
                .toolName("executeCommand")
                .content(content)
                .tokenCount(50)
                .createdAt(new Date())
                .build();
    }

    private ChatMessageEntity entity(String role, String content) {
        return ChatMessageEntity.builder()
                .sessionId("s-fold-1")
                .role(role)
                .content(content)
                .tokenCount(10)
                .createdAt(new Date())
                .build();
    }
}