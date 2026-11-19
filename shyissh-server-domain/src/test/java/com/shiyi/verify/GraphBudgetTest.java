package com.shiyi.verify;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.shiyi.domain.agent.graph.GraphStateKeys;
import com.shiyi.domain.agent.graph.node.ToolNode;
import com.shiyi.domain.agent.graph.support.FakeSshToolService;
import com.shiyi.domain.agent.graph.support.GraphDispatchService;
import com.shiyi.domain.agent.graph.support.GraphSshToolService;
import com.shiyi.domain.agent.graph.support.TokenStreamBridge;
import com.shiyi.domain.ssh.adapter.port.ITerminalSessionPort;
import com.shiyi.domain.ssh.model.entity.TerminalSessionEntity;
import com.shiyi.domain.ssh.service.ISshTerminalService;
import org.junit.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预算与节流的验证。
 * <p>
 * 图链路自己管循环，也就必须自己管"别再往下跑"：工具调用次数、token 用量、命令超时，
 * 这三处只要有一处失守，模型就能把一次对话拖成无限循环或天价账单。
 * 本测试盯住三者的边界行为。
 *
 * @author shiyi
 */
public class GraphBudgetTest {

    /**
     * 工具调用次数用尽后：本次调用不执行，直接回一句让模型收口的话。
     */
    @Test
    public void toolNodeShouldRefuseWhenToolCallBudgetExhausted() throws Exception {
        FakeSshToolService fake = new FakeSshToolService();
        ToolNode node = new ToolNode(new GraphSshToolService(mock(ISshTerminalService.class), fake));
        inject(node, "dispatchService", mock(GraphDispatchService.class));

        OverAllState state = state(Map.of(
                GraphStateKeys.TOOL_CALLS, List.of(call("call-1", "{\"command\":\"df -h\"}")),
                GraphStateKeys.TOOL_CALL_COUNT, 1,
                GraphStateKeys.MAX_TOOL_CALLS, 1,
                GraphStateKeys.TERMINAL_SESSION_ID, "",
                GraphStateKeys.SESSION_ID, "sess-1",
                GraphStateKeys.USER_ID, "user-1",
                GraphStateKeys.AGENT_NAME, "sshOperator",
                GraphStateKeys.DEPTH, 0));

        Map<String, Object> delta = node.action().apply(state, RunnableConfig.builder().build()).get();

        assertTrue("超预算的工具调用必须给出上限提示",
                outputs(delta).get("call-1").contains("已达上限"));
        assertTrue("超预算的工具调用不能再执行", fake.executed().isEmpty());
    }

    /**
     * 正常执行一次工具：计数 +1，供下一轮判断预算。
     */
    @Test
    public void toolNodeShouldCountExecutedToolCalls() throws Exception {
        FakeSshToolService fake = new FakeSshToolService();
        ToolNode node = new ToolNode(new GraphSshToolService(mock(ISshTerminalService.class), fake));
        inject(node, "dispatchService", mock(GraphDispatchService.class));

        OverAllState state = state(Map.of(
                GraphStateKeys.TOOL_CALLS, List.of(call("call-2", "{\"command\":\"df -h\"}")),
                GraphStateKeys.TOOL_CALL_COUNT, 0,
                GraphStateKeys.MAX_TOOL_CALLS, 20,
                GraphStateKeys.TERMINAL_SESSION_ID, "",
                GraphStateKeys.SESSION_ID, "sess-2",
                GraphStateKeys.USER_ID, "user-2",
                GraphStateKeys.AGENT_NAME, "sshOperator",
                GraphStateKeys.DEPTH, 0));

        Map<String, Object> delta = node.action().apply(state, RunnableConfig.builder().build()).get();

        assertEquals(1, delta.get(GraphStateKeys.TOOL_CALL_COUNT));
        assertEquals(List.of("df -h"), fake.executed());
    }

    /**
     * 命令耗时触到超时阈值：输出前带上"可能不完整"的提示，而不是静默返回半截结果。
     */
    @Test
    public void sshToolShouldHintWhenCommandReachesTimeout() throws Exception {
        ISshTerminalService ssh = mock(ISshTerminalService.class);
        when(ssh.getTerminalSession("term-1")).thenReturn(mock(TerminalSessionEntity.class));

        ITerminalSessionPort port = mock(ITerminalSessionPort.class);
        when(port.sessionExists("term-1")).thenReturn(true);
        // 故意睡 5ms：把"实际耗时 >= 超时阈值"这件事变成确定事实，避免毫秒抖动。
        when(port.executeCommandAndWait(eq("term-1"), eq("sleep 5"), anyLong())).thenAnswer(invocation -> {
            Thread.sleep(5);
            return "partial output";
        });

        GraphSshToolService service = new GraphSshToolService(ssh, new FakeSshToolService());
        inject(service, "terminalSessionPort", port);
        // 1ms 阈值：任何一次真实调用都会触线，便于稳定验证提示分支。
        inject(service, "commandTimeoutMs", 1L);

        String output = service.execute("sleep 5", "term-1");

        assertTrue("超时输出必须带提示", output.contains("可能不完整"));
        assertTrue("已收集到的输出不能丢", output.contains("partial output"));
        verify(port).executeCommandAndWait(eq("term-1"), eq("sleep 5"), anyLong());
    }

    /**
     * 用量桥：每轮上报的是绝对值，覆盖写；快照供 done 事件带给前端。
     */
    @Test
    public void bridgeShouldSnapshotUsage() {
        TokenStreamBridge bridge = new TokenStreamBridge();
        bridge.recordUsage(100, 20, 120);
        bridge.recordUsage(180, 45, 225);

        Map<String, Object> usage = bridge.usage();
        assertEquals(180, usage.get("promptTokens"));
        assertEquals(45, usage.get("completionTokens"));
        assertEquals(225, usage.get("totalTokens"));
    }

    private static AssistantMessage.ToolCall call(String id, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", "executeCommand", arguments);
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