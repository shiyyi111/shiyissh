package com.shiyi.verify;

import com.shiyi.domain.agent.graph.support.BatchDispatchRequest;
import com.shiyi.domain.agent.graph.support.DispatchResult;
import com.shiyi.domain.agent.graph.support.GraphDispatchService;
import com.shiyi.domain.agent.graph.support.GraphOrchestrator;
import com.shiyi.domain.agent.graph.support.TokenStreamBridge;
import org.junit.Test;
import org.mockito.ArgumentMatchers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批量派发的并发行为：限流靠"槽位补位"，不靠"切片 + 整片 join"。
 * <p>
 * 旧实现把就绪任务按 maxConcurrency 切片，片内 {@code allOf().join()} 等齐再发下一片。
 * 8 个任务就变成两片，片时长由最慢的那个决定 —— 一个子智能体跑 120 秒，同片另外 3 个槽位
 * 有 115 秒是空转的，而后面 4 个任务本可以立刻开始。
 * <p>
 * 这里用两个用例把行为钉住：① 槽位一空就补位；② 总并发不超上限。
 */
public class GraphOrchestratorConcurrencyTest {

    private static final String SLOW_AGENT = "sshChangeAgent";

    @Test
    public void shouldStartNextTaskAsSoonAsSlotFreesInsteadOfWaitingForWholeSlice() throws Exception {
        GraphDispatchService dispatchService = mock(GraphDispatchService.class);
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        AtomicInteger fastStarted = new AtomicInteger();

        when(dispatchService.dispatchDetailed(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString(), anyInt(),
                ArgumentMatchers.<TokenStreamBridge>isNull()))
                .thenAnswer(invocation -> {
                    String agent = invocation.getArgument(0);
                    if (SLOW_AGENT.equals(agent)) {
                        slowStarted.countDown();
                        // 占住槽位直到测试放行，模拟"一个子智能体跑两分钟"。
                        releaseSlow.await(10, TimeUnit.SECONDS);
                    } else {
                        fastStarted.incrementAndGet();
                        Thread.sleep(20);
                    }
                    return new DispatchResult(agent, DispatchResult.STATUS_OK, "ok", "completed", 10L, 5, 1);
                });

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            GraphOrchestrator orchestrator = new GraphOrchestrator(dispatchService, pool);
            List<BatchDispatchRequest.TaskSpec> specs = List.of(
                    new BatchDispatchRequest.TaskSpec("t1", SLOW_AGENT, "慢任务", List.of()),
                    new BatchDispatchRequest.TaskSpec("t2", "sshDiagnosisAgent", "快任务 1", List.of()),
                    new BatchDispatchRequest.TaskSpec("t3", "sshDiagnosisAgent", "快任务 2", List.of()),
                    new BatchDispatchRequest.TaskSpec("t4", "sshDiagnosisAgent", "快任务 3", List.of()));

            CompletableFuture<GraphOrchestrator.BatchResult> future = CompletableFuture.supplyAsync(
                    () -> orchestrator.executeBatchDetailed("s-1", "u-1", "term-1", 0, "trace-1",
                            specs, 2, false, "host-1", 24000, null),
                    pool);

            assertTrue("慢任务应立刻开跑", slowStarted.await(3, TimeUnit.SECONDS));

            // 并发=2：慢任务占 1 个槽位，另一个槽位应把 3 个快任务依次补位跑完。
            // 切片实现里第 2 片必须等第 1 片（含慢任务）整体结束，这段等待期间最多只启动 1 个快任务。
            long deadline = System.currentTimeMillis() + 3000;
            while (fastStarted.get() < 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals("槽位空出后应立刻补位启动下一个任务，而不是等整片 join 完",
                    3, fastStarted.get());

            releaseSlow.countDown();
            GraphOrchestrator.BatchResult result = future.get(5, TimeUnit.SECONDS);
            assertTrue(result.text(), result.text().contains("4/4 成功"));
        } finally {
            releaseSlow.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    public void shouldNeverExceedConcurrencyLimit() throws Exception {
        GraphDispatchService dispatchService = mock(GraphDispatchService.class);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        when(dispatchService.dispatchDetailed(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString(), anyInt(),
                ArgumentMatchers.<TokenStreamBridge>isNull()))
                .thenAnswer(invocation -> {
                    int now = inFlight.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    try {
                        Thread.sleep(30);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                    String agent = invocation.getArgument(0);
                    return new DispatchResult(agent, DispatchResult.STATUS_OK, "ok", "completed", 30L, 5, 1);
                });

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            GraphOrchestrator orchestrator = new GraphOrchestrator(dispatchService, pool);
            List<BatchDispatchRequest.TaskSpec> specs = new ArrayList<>();
            for (int i = 1; i <= 8; i++) {
                specs.add(new BatchDispatchRequest.TaskSpec("t" + i, "sshDiagnosisAgent", "任务 " + i, List.of()));
            }

            GraphOrchestrator.BatchResult result = orchestrator.executeBatchDetailed(
                    "s-2", "u-2", "term-2", 0, "trace-2", specs, 3, false, "host-1", 24000, null);

            assertTrue(result.text(), result.text().contains("8/8 成功"));
            assertTrue("并发峰值不应超过 maxConcurrency=3，实际=" + peak.get(), peak.get() <= 3);
            assertTrue("8 个独立任务在并发 3 下应真的并行，实际峰值=" + peak.get(), peak.get() > 1);
        } finally {
            pool.shutdownNow();
        }
    }
}