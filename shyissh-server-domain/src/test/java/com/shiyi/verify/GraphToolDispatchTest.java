package com.shiyi.verify;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.shiyi.domain.agent.graph.GraphStateKeys;
import com.shiyi.domain.agent.graph.node.ToolNode;
import com.shiyi.domain.agent.graph.support.FakeSshToolService;
import com.shiyi.domain.agent.graph.support.DispatchResult;
import com.shiyi.domain.agent.graph.support.GraphDispatchService;
import com.shiyi.domain.agent.graph.support.GraphSshToolService;
import com.shiyi.domain.ssh.service.ISshTerminalService;
import org.junit.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具执行节点的分派验证。
 * <p>
 * 这里专门守一个曾经的缺陷：{@code ToolNode} 早期把所有 toolCall 都当
 * {@code executeCommand} 解析，模型一旦选择 {@code dispatchSubAgent}，
 * {@code parseCommand} 取不到 {@code command} 字段而返回空串，
 * 最终只得到"(空命令，未执行)"，子智能体永远不会被真正派发。
 * <p>
 * 修复后按 {@code call.name()} 分流，本测试确保这个分流不被改回去。
 *
 * @author shiyi
 */
public class GraphToolDispatchTest {

    /**
     * 模型调用 dispatchSubAgent 时：必须走 GraphDispatchService，且不能退化成执行空命令。
     */
    @Test
    public void toolNodeShouldDispatchSubAgentInsteadOfRunningEmptyCommand() throws Exception {
        FakeSshToolService fake = new FakeSshToolService();
        ToolNode node = new ToolNode(new GraphSshToolService(mock(ISshTerminalService.class), fake));

        GraphDispatchService dispatchService = mock(GraphDispatchService.class);
        when(dispatchService.dispatchDetailed(eq("sshDiagnosisAgent"), eq("查一下磁盘为什么满"),
                eq("sess-1"), eq("user-1"), eq("term-1"), eq(0), anyString(), anyString(), anyInt(), org.mockito.ArgumentMatchers.<com.shiyi.domain.agent.graph.support.TokenStreamBridge>isNull()))
                .thenReturn(new DispatchResult("sshDiagnosisAgent", DispatchResult.STATUS_OK,
                        "磁盘 92% 被 /var/log 占满，建议清理 journal 日志", "completed", 1200L, 800, 1));
        inject(node, "dispatchService", dispatchService);

        OverAllState state = state(Map.of(
                GraphStateKeys.TOOL_CALLS, List.of(new AssistantMessage.ToolCall(
                        "call-sub-1", "function", "dispatchSubAgent",
                        "{\"agent\":\"sshDiagnosisAgent\",\"task\":\"查一下磁盘为什么满\"}")),
                GraphStateKeys.TERMINAL_SESSION_ID, "term-1",
                GraphStateKeys.SESSION_ID, "sess-1",
                GraphStateKeys.USER_ID, "user-1",
                GraphStateKeys.AGENT_NAME, "sshOperator",
                GraphStateKeys.DEPTH, 0));

        Map<String, Object> delta = node.action().apply(state, RunnableConfig.builder().build()).get();
        Map<String, String> outputs = outputs(delta);

        assertEquals("子智能体结论应作为工具结果回填（JSON 信封形式，父模型可解析 status）",
                "sshDiagnosisAgent", com.alibaba.fastjson2.JSON.parseObject(outputs.get("call-sub-1")).getString("agent"));
        assertEquals("磁盘 92% 被 /var/log 占满，建议清理 journal 日志",
                com.alibaba.fastjson2.JSON.parseObject(outputs.get("call-sub-1")).getString("summary"));
        verify(dispatchService).dispatchDetailed(eq("sshDiagnosisAgent"), eq("查一下磁盘为什么满"),
                eq("sess-1"), eq("user-1"), eq("term-1"), eq(0), anyString(), anyString(), anyInt(), org.mockito.ArgumentMatchers.<com.shiyi.domain.agent.graph.support.TokenStreamBridge>isNull());
        assertTrue("dispatchSubAgent 不能被当成 shell 命令执行", fake.executed().isEmpty());
    }

    /**
     * 模型调用 executeCommand 时：仍然走 SSH 工具，行为不能被分派逻辑破坏。
     */
    @Test
    public void toolNodeShouldStillExecuteCommand() throws Exception {
        FakeSshToolService fake = new FakeSshToolService();
        ToolNode node = new ToolNode(new GraphSshToolService(mock(ISshTerminalService.class), fake));
        GraphDispatchService dispatchService = mock(GraphDispatchService.class);
        inject(node, "dispatchService", dispatchService);

        OverAllState state = state(Map.of(
                GraphStateKeys.TOOL_CALLS, List.of(new AssistantMessage.ToolCall(
                        "call-cmd-1", "function", "executeCommand", "{\"command\":\"df -h\"}")),
                GraphStateKeys.TERMINAL_SESSION_ID, "",
                GraphStateKeys.SESSION_ID, "sess-2",
                GraphStateKeys.USER_ID, "user-2",
                GraphStateKeys.AGENT_NAME, "sshOperator",
                GraphStateKeys.DEPTH, 0));

        Map<String, Object> delta = node.action().apply(state, RunnableConfig.builder().build()).get();
        Map<String, String> outputs = outputs(delta);

        assertTrue("命令输出应包含 df 的真实数字", outputs.get("call-cmd-1").contains("48%"));
        assertEquals(List.of("df -h"), fake.executed());
        verify(dispatchService, org.mockito.Mockito.never())
                .dispatchDetailed(anyString(), anyString(), anyString(), anyString(), anyString(),
                        org.mockito.ArgumentMatchers.anyInt(), anyString(), anyString(),
                        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.<com.shiyi.domain.agent.graph.support.TokenStreamBridge>isNull());
    }

    /**
     * [guard] 判 CONFIRM 的工具调用不能被真正执行。
     * <p>
     * 老版 {@code MyTestPlugin#guardCommand} 对 CONFIRM 的处理是"回一句确认文案、命令不发"，
     * 图链路必须在 ToolNode 这一层兑现——否则危险命令只是"被判了重"，照样跑。
     */
    @Test
    public void toolNodeShouldSkipConfirmVerdictCall() throws Exception {
        FakeSshToolService fake = new FakeSshToolService();
        ToolNode node = new ToolNode(new GraphSshToolService(mock(ISshTerminalService.class), fake));

        String callId = "call-rm-1";
        OverAllState state = state(Map.of(
                GraphStateKeys.TOOL_CALLS, List.of(new AssistantMessage.ToolCall(
                        callId, "function", "executeCommand", "{\"command\":\"rm -rf /home/app/cache\"}")),
                GraphStateKeys.GUARD_VERDICTS, Map.of(callId, "CONFIRM"),
                GraphStateKeys.GUARD_REASONS, Map.of(callId, "递归删除目录，存在误删风险"),
                GraphStateKeys.TERMINAL_SESSION_ID, "",
                GraphStateKeys.SESSION_ID, "sess-3",
                GraphStateKeys.USER_ID, "user-3",
                GraphStateKeys.AGENT_NAME, "sshOperator",
                GraphStateKeys.DEPTH, 0));

        Map<String, Object> delta = node.action().apply(state, RunnableConfig.builder().build()).get();
        Map<String, String> outputs = outputs(delta);

        assertTrue("CONFIRM 档必须给出待确认提示", outputs.get(callId).contains("待人工确认"));
        assertTrue("CONFIRM 档的命令绝不能被执行", fake.executed().isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> outputs(Map<String, Object> delta) {
        return (Map<String, String>) delta.get(GraphStateKeys.TOOL_OUTPUTS);
    }

    private static OverAllState state(Map<String, Object> input) {
        return new OverAllState(new HashMap<>(input));
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}