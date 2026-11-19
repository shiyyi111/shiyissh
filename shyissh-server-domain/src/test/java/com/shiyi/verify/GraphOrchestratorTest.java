package com.shiyi.verify;

import com.shiyi.domain.agent.graph.support.BatchDispatchRequest;
import com.shiyi.domain.agent.graph.support.DispatchResult;
import com.shiyi.domain.agent.graph.support.GraphDispatchService;
import com.shiyi.domain.agent.graph.support.GraphOrchestrator;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批量并行编排（L2）：DAG 调度、失败级联、并发上限、参数校验。
 * <p>
 * 这里不跑真模型也不跑真线程池：{@code Executor} 用同步实现，保证"同一轮内按顺序执行"，
 * 从而可以确定性地断言调度顺序与级联跳过行为。真正的并发在 {@link GraphOrchestrator} 里
 * 由 {@code CompletableFuture.runAsync(..., executor)} 承担，不在这里验证。
 *
 * @author shiyi
 */
public class GraphOrchestratorTest {

    private GraphDispatchService dispatchService;
    private GraphOrchestrator orchestrator;

    /** 记录每次真正下发的 (agentName, request)，用于断言顺序。 */
    private final List<String> executed = new ArrayList<>();

    @Before
    public void setUp() {
        dispatchService = mock(GraphDispatchService.class);
        Executor direct = Runnable::run;
        orchestrator = new GraphOrchestrator(dispatchService, direct);
    }

    @Test
    public void shouldRejectEmptyTasks() {
        String result = orchestrator.executeBatch("s1", "u1", "", 0, List.of(), null, null);
        assertEquals("（批量派发失败：任务列表为空）", result);
        verify(dispatchService, never()).dispatchDetailed(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyInt(), org.mockito.ArgumentMatchers.<com.shiyi.domain.agent.graph.support.TokenStreamBridge>isNull());
    }

    @Test
    public void shouldRunDependencyInOrder() {
        answerAlways("ok");
        List<BatchDispatchRequest.TaskSpec> specs = List.of(
                spec("t1", "sshDiagnosisAgent", "查磁盘", List.of()),
                spec("t2", "sshChangeAgent", "扩容", List.of("t1")));

        String result = orchestrator.executeBatch("s1", "u1", "term-1", 0, specs, null, null);

        assertEquals(List.of("sshDiagnosisAgent:查磁盘", "sshChangeAgent:扩容"), executed);
        assertTrue(result.contains("【批量派发结果】2/2 成功"));
        assertTrue(result.contains("t1（sshDiagnosisAgent）成功"));
        assertTrue(result.contains("t2（sshChangeAgent）成功"));
    }

    @Test
    public void shouldSkipDownstreamWhenDependencyFailed() {
        // 注意 stub 顺序：先给通用答案，再用更具体的 stub 覆盖（Mockito 后注册者优先）
        answerAlways("ok");
        // 第一个任务派发失败（status=error），下游必须级联 SKIP 且不再下发
        when(dispatchService.dispatchDetailed(eq("sshDiagnosisAgent"), anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyInt(), org.mockito.ArgumentMatchers.<com.shiyi.domain.agent.graph.support.TokenStreamBridge>isNull()))
                .thenAnswer(invocation -> {
                    executed.add(invocation.getArgument(0) + ":" + invocation.getArgument(1));
                    return DispatchResult.failed("sshDiagnosisAgent", "（派发失败：未找到名为 sshDiagnosisAgent 的智能体）");
                });

        List<BatchDispatchRequest.TaskSpec> specs = List.of(
                spec("t1", "sshDiagnosisAgent", "查磁盘", List.of()),
                spec("t2", "sshChangeAgent", "扩容", List.of("t1")));

        String result = orchestrator.executeBatch("s1", "u1", "term-1", 0, specs, null, null);

        // 只有第一个任务真正下发，下游被级联跳过
        assertEquals(List.of("sshDiagnosisAgent:查磁盘"), executed);
        verify(dispatchService, times(1))
                .dispatchDetailed(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyInt(), org.mockito.ArgumentMatchers.<com.shiyi.domain.agent.graph.support.TokenStreamBridge>isNull());
        assertTrue(result.contains("0/2 成功"));
        assertTrue(result.contains("t2（sshChangeAgent）已跳过"));
    }

    @Test
    public void shouldRunIndependentTasksInSameRound() {
        answerAlways("ok");
        List<BatchDispatchRequest.TaskSpec> specs = List.of(
                spec("t1", "sshDiagnosisAgent", "看磁盘", List.of()),
                spec("t2", "sshDiagnosisAgent", "看内存", List.of()));

        String result = orchestrator.executeBatch("s1", "u1", "term-1", 0, specs, 2, null);

        assertEquals(2, executed.size());
        assertTrue(result.contains("2/2 成功"));
    }

    @Test
    public void shouldSumChildTokensIntoBatchResult() {
        answerAlways("ok");
        List<BatchDispatchRequest.TaskSpec> specs = List.of(
                spec("t1", "sshDiagnosisAgent", "看磁盘", List.of()),
                spec("t2", "sshDiagnosisAgent", "看内存", List.of()));

        GraphOrchestrator.BatchResult batch =
                orchestrator.executeBatchDetailed("s1", "u1", "term-1", 0, specs, 2, null);

        assertTrue(batch.text().contains("2/2 成功"));
        // 两个子任务各 100 token：父级账上必须看得见子级消耗，
        // 否则一次批量派发能把总成本放大数倍而父级预算毫无反应
        assertEquals(200, batch.tokens());
    }

    @Test
    public void shouldRejectTooManyTasks() {
        List<BatchDispatchRequest.TaskSpec> specs = new ArrayList<>();
        for (int i = 0; i < GraphOrchestrator.MAX_TASKS + 1; i++) {
            specs.add(spec("t" + i, "sshDiagnosisAgent", "任务" + i, List.of()));
        }
        String result = orchestrator.executeBatch("s1", "u1", "term-1", 0, specs, null, null);
        assertTrue(result.startsWith("（批量派发失败：一次最多派发"));
        assertTrue(executed.isEmpty());
    }

    @Test
    public void shouldSkipRemainingTasksOnFailFast() {
        answerAlways("ok");
        when(dispatchService.dispatchDetailed(eq("bad"), anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyInt(), org.mockito.ArgumentMatchers.<com.shiyi.domain.agent.graph.support.TokenStreamBridge>isNull()))
                .thenReturn(DispatchResult.failed("bad", "（派发失败：模拟失败）"));

        List<BatchDispatchRequest.TaskSpec> specs = List.of(
                spec("t1", "bad", "必失败的任务", List.of()),
                spec("t2", "sshDiagnosisAgent", "后续任务", List.of()));

        String result = orchestrator.executeBatch("s1", "u1", "term-1", 0, specs, 1, true);

        // t1 失败后 failFast 生效，t2 不再下发
        verify(dispatchService, never())
                .dispatchDetailed(eq("sshDiagnosisAgent"), anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyInt(), org.mockito.ArgumentMatchers.<com.shiyi.domain.agent.graph.support.TokenStreamBridge>isNull());
        assertTrue(result.contains("0/2 成功"));
        assertTrue(result.contains("t2（sshDiagnosisAgent）已跳过"));
    }

    private void answerAlways(String answer) {
        when(dispatchService.dispatchDetailed(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyInt(), org.mockito.ArgumentMatchers.<com.shiyi.domain.agent.graph.support.TokenStreamBridge>isNull()))
                .thenAnswer(invocation -> {
                    executed.add(invocation.getArgument(0) + ":" + invocation.getArgument(1));
                    // 每个子任务固定 100 token，便于断言"父级能看见子级消耗"
                    return new DispatchResult(invocation.getArgument(0), DispatchResult.STATUS_OK,
                            answer, "completed", 10L, 100, 1);
                });
    }

    private BatchDispatchRequest.TaskSpec spec(String taskId, String agentName, String request,
                                               List<String> dependsOn) {
        return new BatchDispatchRequest.TaskSpec(taskId, agentName, request, dependsOn);
    }
}