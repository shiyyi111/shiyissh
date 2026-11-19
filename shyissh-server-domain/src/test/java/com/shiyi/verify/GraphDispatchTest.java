package com.shiyi.verify;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.shiyi.domain.agent.graph.SshAgentGraph;
import com.shiyi.domain.agent.graph.support.DispatchResult;
import com.shiyi.domain.agent.graph.support.GraphDispatchService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 子智能体派发：边界条件与"复用同一张图"的行为验证。
 * <p>
 * 这里不跑真模型，只验证控制流：
 * 参数非法/深度超限/图未就绪时必须**快速返回文本**（不能抛异常进图），
 * 正常时要把子任务塞进 state 用新 threadId 跑一次图。
 *
 * @author shiyi
 */
public class GraphDispatchTest {

    private GraphDispatchService service;
    private SshAgentGraph graph;

    @Before
    public void setUp() throws Exception {
        service = new GraphDispatchService();
        graph = mock(SshAgentGraph.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<SshAgentGraph> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(graph);

        inject(service, "sshAgentGraphProvider", provider);
        // agentRegistry 保持 null：不校验名称，聚焦控制流
    }

    @Test
    public void shouldRejectEmptyTask() {
        String answer = service.dispatch("sshDiagnosisAgent", "  ", "s1", "u1", "t1", 0);
        assertTrue(answer.contains("子任务描述为空"));
        verify(graph, never()).stream(any(), any());
    }

    @Test
    public void shouldRejectWhenDepthLimitReached() {
        String answer = service.dispatch("sshDiagnosisAgent", "查磁盘", "s1", "u1", "t1",
                GraphDispatchService.MAX_DEPTH);
        assertTrue(answer.contains("最大派发层级"));
        verify(graph, never()).stream(any(), any());
    }

    @Test
    public void shouldReturnHintWhenGraphMissing() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<SshAgentGraph> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        inject(service, "sshAgentGraphProvider", emptyProvider);

        // 返回值是给模型看的 JSON 信封：失败也要能被模型读出 status=error，
        // 这样父模型才知道"重试没用"，而不是反复派发同一个子任务。
        DispatchResult result = service.dispatchDetailed("sshDiagnosisAgent", "查磁盘", "s1", "u1", "t1", 0);
        assertEquals(DispatchResult.STATUS_ERROR, result.status());
        assertEquals("（派发失败：图未初始化）", result.summary());

        String envelope = service.dispatch("sshDiagnosisAgent", "查磁盘", "s1", "u1", "t1", 0);
        assertTrue(envelope.contains("\"status\":\"error\""));
        assertTrue(envelope.contains("图未初始化"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldRunGraphWithSubSessionAndDepthPlusOne() {
        when(graph.stream(any(Map.class), any(RunnableConfig.class)))
                .thenReturn(reactor.core.publisher.Flux.empty());

        DispatchResult result = service.dispatchDetailed("sshDiagnosisAgent", "看一下磁盘", "parent-1", "u1", "term-1", 0);

        // 图没产出任何 token -> 明确告知"未给出结论"，而不是返回空串；
        // 状态是 empty 而不是 error：子图确实跑了，只是没结论，父模型可以换个问法重派。
        assertEquals(DispatchResult.STATUS_EMPTY, result.status());
        assertEquals("（子智能体未给出结论）", result.summary());

        // 信封字段是给模型看的契约：agent 标明谁干的、status 标明能不能重试
        String envelope = result.toEnvelope();
        assertTrue(envelope.contains("\"agent\":\"sshDiagnosisAgent\""));
        assertTrue(envelope.contains("\"status\":\"empty\""));

        org.mockito.ArgumentCaptor<Map<String, Object>> inputCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.ArgumentCaptor<RunnableConfig> configCaptor =
                org.mockito.ArgumentCaptor.forClass(RunnableConfig.class);
        verify(graph).stream(inputCaptor.capture(), configCaptor.capture());

        Map<String, Object> input = inputCaptor.getValue();
        assertEquals("parent-1#sub-sshDiagnosisAgent-1", input.get("sessionId"));
        assertEquals("sshDiagnosisAgent", input.get("agentName"));
        assertEquals(1, input.get("depth"));
        assertEquals("term-1", input.get("terminalSessionId"));
        assertTrue(configCaptor.getValue().threadId().orElse("").startsWith("parent-1#sub-sshDiagnosisAgent-"));
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}