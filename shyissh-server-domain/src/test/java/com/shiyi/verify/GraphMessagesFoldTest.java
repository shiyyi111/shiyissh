package com.shiyi.verify;

import com.shiyi.domain.agent.graph.support.GraphMessages;
import org.junit.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 重复工具输出折叠的单元测试。
 * <p>
 * 折叠是纯函数，不需要起图：构造 "tool_calls + tool 响应" 消息对，断言第二次的输出被替换、
 * tool_call_id 保持不变（配对不能坏）、且重复执行是幂等的。
 *
 * @author shiyi
 */
public class GraphMessagesFoldTest {

    private static AssistantMessage call(String id, String command) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", "executeCommand",
                        "{\"command\":\"" + command + "\"}")))
                .build();
    }

    private static ToolResponseMessage result(String id, String output) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, "executeCommand", output)))
                .build();
    }

    private static String responseData(Message message) {
        return ((ToolResponseMessage) message).getResponses().get(0).responseData();
    }

    private static String responseId(Message message) {
        return ((ToolResponseMessage) message).getResponses().get(0).id();
    }

    /** 同一命令第二次执行：输出被折叠，但 tool_call_id 保持不变。 */
    @Test
    public void shouldFoldRepeatedCommandButKeepToolCallId() {
        List<Message> messages = List.of(
                call("c1", "df -h"), result("c1", "Filesystem 100G 50G used"),
                call("c2", "df -h"), result("c2", "Filesystem 100G 50G used"));

        List<Message> folded = GraphMessages.foldDuplicateToolOutputs(messages);

        assertEquals("折叠不删消息，只改内容", messages.size(), folded.size());
        assertEquals("Filesystem 100G 50G used", responseData(folded.get(1)));
        assertTrue("第二次输出应被折叠", responseData(folded.get(3)).contains("[重复调用]"));
        assertTrue("折叠说明要指明是哪一次的结果", responseData(folded.get(3)).contains("第 1 次"));
        assertEquals("tool_call_id 不能变，否则配对断裂", "c2", responseId(folded.get(3)));
    }

    /** 不同命令即使参数相似也不折叠。 */
    @Test
    public void shouldNotFoldDifferentCommands() {
        List<Message> messages = List.of(
                call("c1", "df -h"), result("c1", "out-1"),
                call("c2", "free -m"), result("c2", "out-2"));

        // 没有重复时原样返回同一个实例，避免每轮都复制一份消息列表
        assertSame(messages, GraphMessages.foldDuplicateToolOutputs(messages));
        assertFalse(responseData(messages.get(3)).contains("[重复调用]"));
    }

    /** 折叠是幂等的：已折叠的消息再跑一遍结果不变。 */
    @Test
    public void shouldBeIdempotent() {
        List<Message> messages = List.of(
                call("c1", "df -h"), result("c1", "out-1"),
                call("c2", "df -h"), result("c2", "out-1"));

        List<Message> once = GraphMessages.foldDuplicateToolOutputs(messages);
        List<Message> twice = GraphMessages.foldDuplicateToolOutputs(once);

        assertTrue("第一次应发生折叠", responseData(once.get(3)).contains("[重复调用]"));
        assertEquals(responseData(once.get(3)), responseData(twice.get(3)));
        assertEquals(responseData(once.get(1)), responseData(twice.get(1)));
    }

    /** 同一条命令两次执行，但输出不同：这是新信息，不能折叠。 */
    @Test
    public void shouldNotFoldWhenOutputDiffers() {
        List<Message> messages = List.of(
                call("c1", "df -h"), result("c1", "Filesystem 100G 50G used"),
                // 第二次磁盘被写满了，输出完全不同——这正是排查要看的差异
                call("c2", "df -h"), result("c2", "Filesystem 100G 99G used"));

        // 不折叠时原样返回同一个实例
        assertSame(messages, GraphMessages.foldDuplicateToolOutputs(messages));
        assertEquals("Filesystem 100G 99G used", responseData(messages.get(3)));
    }

    /** 三次同命令：只有输出与第一次完全相同的那次被折叠。 */
    @Test
    public void shouldFoldOnlyIdenticalOutputs() {
        List<Message> messages = List.of(
                call("c1", "df -h"), result("c1", "out-50G"),
                call("c2", "df -h"), result("c2", "out-99G"),
                call("c3", "df -h"), result("c3", "out-50G"));

        List<Message> folded = GraphMessages.foldDuplicateToolOutputs(messages);

        assertEquals("out-50G", responseData(folded.get(1)));
        assertEquals("输出不同的一次保留原样", "out-99G", responseData(folded.get(3)));
        assertTrue("与第一次完全相同的那次被折叠", responseData(folded.get(5)).contains("[重复调用]"));
        assertTrue("占位文案说明是输出相同而不是命令相同", responseData(folded.get(5)).contains("两次输出完全相同"));
    }

    /** 没有 tool_calls 的消息列表直接返回，不做任何处理。 */
    @Test
    public void shouldIgnoreMessagesWithoutToolCalls() {
        List<Message> messages = List.of(result("c1", "orphan-output"));
        assertSame(messages, GraphMessages.foldDuplicateToolOutputs(messages));
    }
}