package com.shiyi.verify;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.shiyi.domain.agent.graph.SshAgentGraph;
import com.shiyi.domain.agent.graph.support.DispatchResult;
import com.shiyi.domain.agent.graph.support.GraphDispatchService;
import com.shiyi.domain.agent.graph.support.TokenStreamBridge;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 子智能体派发的超时与重试（ADK 期的韧性能力，SAA 重写时丢失，2026-11-14 补回）。
 * <p>
 * 这里只验证控制流，不跑真模型：
 * <ul>
 *   <li>超时 → 按退避重试 → 成功（attempts 要如实回报）；</li>
 *   <li>执行异常 → 重试 → 成功；</li>
 *   <li>重试耗尽 → status=error、stopReason=timeout、文案带尝试次数；</li>
 *   <li>前置校验拒绝 → 一次都不跑，attempts=0；</li>
 *   <li>每次尝试用独立 threadId，不能挂在上一轮的检查点上续跑。</li>
 * </ul>
 *
 * @author shiyi
 */
public class GraphDispatchRetryTest {

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

        // 缺省配置对单测来说太慢（120s 超时、1s 退避），改成毫秒级。
        setField(service, "childTimeoutMs", 80L);
        setField(service, "maxRetries", 2);
        setField(service, "retryBackoffMs", 1L);
        setField(service, "maxBackoffMs", 5L);
    }

    @Test
    public void shouldNotRetryTimeoutEvenIfNextRunWouldSucceed() {
        NodeOutput output = mock(NodeOutput.class);
        // 第一次卡住（超时），第二次正常返回；顺带往 token 桥里推一段结论，
        // 让结果走 STATUS_OK 而不是 STATUS_EMPTY。
        when(graph.stream(any(Map.class), any(RunnableConfig.class)))
                .thenReturn(Flux.never())
                .thenAnswer(invocation -> {
                    RunnableConfig config = invocation.getArgument(1);
                    Object sink = config.metadata("tokenSink").orElse(null);
                    if (sink instanceof TokenStreamBridge bridge) {
                        bridge.emit("磁盘 92% 被 /var/log 占满");
                    }
                    return Flux.just(output);
                });

        DispatchResult result = service.dispatchDetailed("sshDiagnosisAgent", "查磁盘", "s1", "u1", "t1", 0);

        assertEquals("超时后不再重试，即使下一次可能成功", DispatchResult.STATUS_ERROR, result.status());
        assertEquals("timeout", result.stopReason());
        assertEquals(1, result.attempts());
        verify(graph, times(1)).stream(any(Map.class), any(RunnableConfig.class));
        assertTrue(result.toEnvelope().contains("\"attempts\":1"));
    }

    @Test
    public void shouldRetryAfterExceptionThenSucceed() {
        NodeOutput output = mock(NodeOutput.class);
        when(graph.stream(any(Map.class), any(RunnableConfig.class)))
                .thenReturn(Flux.error(new IllegalStateException("连接被重置")))
                .thenReturn(Flux.just(output));

        DispatchResult result = service.dispatchDetailed("sshDiagnosisAgent", "查磁盘", "s1", "u1", "t1", 0);

        // 成功但没推 token -> empty；关键断言是"重试确实发生了"。
        assertEquals(DispatchResult.STATUS_EMPTY, result.status());
        assertEquals(2, result.attempts());
        verify(graph, times(2)).stream(any(Map.class), any(RunnableConfig.class));
    }

    @Test
    public void shouldNotRetryAfterTimeout() throws Exception {
        setField(service, "maxRetries", 1);
        when(graph.stream(any(Map.class), any(RunnableConfig.class))).thenReturn(Flux.never());

        long start = System.currentTimeMillis();
        DispatchResult result = service.dispatchDetailed("sshDiagnosisAgent", "查磁盘", "s1", "u1", "t1", 0);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(DispatchResult.STATUS_ERROR, result.status());
        assertEquals("timeout", result.stopReason());
        assertEquals("超时不应重试：子图可能已经跑了一半，重放写命令风险更高", 1, result.attempts());
        assertTrue(result.summary().contains("超时"));
        assertFalse("只尝试过一次，不该出现重试提示", result.summary().contains("已尝试"));
        verify(graph, times(1)).stream(any(Map.class), any(RunnableConfig.class));
        // 80ms 超时 x 2 次 + 1ms 退避，留足余量但必须明显小于"一次 120s 默认超时"。
        assertTrue("耗时应为毫秒级，实际 " + elapsed + "ms", elapsed < 5_000L);
    }

    @Test
    public void shouldNotRetryPrecheckFailure() {
        DispatchResult result = service.dispatchDetailed("sshDiagnosisAgent", "  ", "s1", "u1", "t1", 0);

        assertEquals(DispatchResult.STATUS_ERROR, result.status());
        assertEquals(0, result.attempts());
        verify(graph, never()).stream(any(Map.class), any(RunnableConfig.class));
    }

    @Test
    public void shouldUseFreshThreadIdForEachAttempt() {
        List<RunnableConfig> configs = new ArrayList<>();
        NodeOutput output = mock(NodeOutput.class);
        when(graph.stream(any(Map.class), any(RunnableConfig.class)))
                .thenReturn(Flux.error(new IllegalStateException("第一次失败")))
                .thenAnswer(invocation -> {
                    configs.add(invocation.getArgument(1));
                    return Flux.just(output);
                });

        service.dispatchDetailed("sshDiagnosisAgent", "查磁盘", "parent-1", "u1", "t1", 0);

        // 第一次的 config 由 thenReturn 提供（拿不到），第二次能拿到；断言它带了 #try2 后缀，
        // 说明重试没有复用上一轮的 threadId（复用会从检查点续跑）。
        assertEquals(1, configs.size());
        String threadId = configs.get(0).threadId().orElse("");
        assertTrue("threadId 应带重试后缀，实际 " + threadId, threadId.endsWith("#try2"));
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        setField(target, fieldName, value);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}