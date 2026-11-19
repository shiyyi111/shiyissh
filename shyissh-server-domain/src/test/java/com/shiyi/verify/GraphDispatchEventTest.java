package com.shiyi.verify;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.shiyi.domain.agent.graph.GraphStateKeys;
import com.shiyi.domain.agent.graph.SshAgentGraph;
import com.shiyi.domain.agent.graph.support.DispatchResult;
import com.shiyi.domain.agent.graph.support.GraphDispatchService;
import com.shiyi.domain.agent.graph.support.TokenStreamBridge;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 子智能体过程的 SSE 转发（L1：结构化事件 + 派发进度，不含 token 流）。
 * <p>
 * 派发是同步阻塞的：父图在等子图跑完这段时间里没有任何 token 产出，前端只看 token 流
 * 会以为请求卡死；子智能体在目标机上跑了什么命令，父流里也完全看不见。
 * 这里验证三件事：
 * <ul>
 *   <li>{@code dispatch_start} / {@code dispatch_end} 成对出现，带 agent 与 depth；</li>
 *   <li>子图的 {@code tool_call} / {@code tool_result} 被转发进来，并打上 agent 标记；</li>
 *   <li>没有父桥（单测、非流式调用）时不转发也不抛异常。</li>
 * </ul>
 *
 * @author shiyi
 */
public class GraphDispatchEventTest {

    private GraphDispatchService service;
    private SshAgentGraph graph;

    @Before
    public void setUp() throws Exception {
        service = new GraphDispatchService();
        graph = mock(SshAgentGraph.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<SshAgentGraph> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(graph);
        setField(service, "sshAgentGraphProvider", provider);

        setField(service, "childTimeoutMs", 2_000L);
        setField(service, "maxRetries", 0);
        setField(service, "retryBackoffMs", 1L);
        setField(service, "maxBackoffMs", 5L);
    }

    @Test
    public void shouldForwardChildToolEventsWithAgentTag() {
        when(graph.stream(any(Map.class), any(RunnableConfig.class))).thenAnswer(invocation -> {
            RunnableConfig config = invocation.getArgument(1);
            TokenStreamBridge child = (TokenStreamBridge) config.metadata("tokenSink").orElse(null);
            if (child != null) {
                child.emitEvent(new LinkedHashMap<>(Map.of("event", "tool_call", "tool", "executeCommand")));
                child.emitEvent(new LinkedHashMap<>(Map.of("event", "tool_result", "tool", "executeCommand")));
                child.emit("结论：磁盘够用");
            }
            return Flux.just(mock(NodeOutput.class));
        });

        TokenStreamBridge parent = new TokenStreamBridge();
        List<Map<String, Object>> received = new CopyOnWriteArrayList<>();
        parent.events().subscribe(received::add);

        service.dispatchDetailed("sshDiagnosisAgent", "查磁盘", "s1", "u1", "t1", 0, "", "", 0, parent);

        List<Map<String, Object>> tools = received.stream()
                .filter(e -> "tool_call".equals(e.get("event")) || "tool_result".equals(e.get("event")))
                .toList();
        assertEquals("两个工具事件都该被转发", 2, tools.size());
        for (Map<String, Object> event : tools) {
            assertEquals("sshDiagnosisAgent", event.get("agent"));
            assertEquals(1, event.get("depth"));
        }
    }

    @Test
    public void shouldEmitDispatchStartAndEnd() {
        when(graph.stream(any(Map.class), any(RunnableConfig.class))).thenAnswer(invocation -> {
            RunnableConfig config = invocation.getArgument(1);
            TokenStreamBridge child = (TokenStreamBridge) config.metadata("tokenSink").orElse(null);
            if (child != null) {
                child.emit("子智能体结论");
            }
            return Flux.just(mock(NodeOutput.class));
        });

        TokenStreamBridge parent = new TokenStreamBridge();
        List<Map<String, Object>> received = new CopyOnWriteArrayList<>();
        parent.events().subscribe(received::add);

        DispatchResult result = service.dispatchDetailed("sshDiagnosisAgent", "查磁盘", "s1", "u1", "t1", 0,
                "", "", 0, parent);

        assertEquals(DispatchResult.STATUS_OK, result.status());
        Map<String, Object> start = received.get(0);
        assertEquals(GraphStateKeys.EVENT_DISPATCH_START, start.get("event"));
        assertEquals("sshDiagnosisAgent", start.get("agent"));
        assertEquals(1, start.get("depth"));

        Map<String, Object> end = received.get(received.size() - 1);
        assertEquals(GraphStateKeys.EVENT_DISPATCH_END, end.get("event"));
        assertEquals("ok", end.get("status"));
        assertEquals(1, end.get("attempts"));
        assertEquals(1, end.get("depth"));
    }

    @Test
    public void shouldTolerateMissingEventSink() {
        when(graph.stream(any(Map.class), any(RunnableConfig.class))).thenAnswer(invocation -> {
            RunnableConfig config = invocation.getArgument(1);
            TokenStreamBridge child = (TokenStreamBridge) config.metadata("tokenSink").orElse(null);
            if (child != null) {
                child.emit("结论");
                child.emitEvent(new LinkedHashMap<>(Map.of("event", "tool_call")));
            }
            return Flux.just(mock(NodeOutput.class));
        });

        // 九参入口 = 不转发；不应该因为"没人接事件"而失败。
        DispatchResult result = service.dispatchDetailed("sshDiagnosisAgent", "查磁盘", "s1", "u1", "t1", 0);

        assertEquals(DispatchResult.STATUS_OK, result.status());
        assertTrue(result.summary().contains("结论"));
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}