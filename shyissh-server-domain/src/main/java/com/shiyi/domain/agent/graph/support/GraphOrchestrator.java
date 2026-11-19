package com.shiyi.domain.agent.graph.support;

import com.shiyi.domain.agent.model.valobj.dynamic.DynamicTask;
import com.shiyi.domain.agent.graph.support.TokenStreamBridge;
import com.shiyi.domain.agent.model.valobj.dynamic.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 批量多智能体编排器。
 * <p>
 * 三层编排能力：
 * <pre>
 *   L2 批量并行： dispatchSubAgents → GraphOrchestrator（DAG + 并发 + failFast）
 *            DynamicPlanDispatchTool     → PlannerAgentBuilder 出计划 → 同一个编排器
 *            AgentWorkflowNode（yml）    → 装配期编译成 Sequential/Parallel/Loop Agent
 *   SAA 版： [llm] 挂 dispatchSubAgents 工具 → 本类 executeBatch(...)
 *            子任务执行 = {@link GraphDispatchService#dispatch}（同一张图、独立 threadId）
 * </pre>
 * 调度策略：每轮筛出"依赖全部 COMPLETED"的就绪任务 → 本轮并发执行（信号量补位，总并发 ≤ maxConcurrency）
 * → 等本轮结束进入下一轮；失败任务只标自身 FAILED，依赖它的下游任务级联 SKIP；
 * failFast=true 时首个失败后不再调度新任务。
 * <p>
 * 两个实现上的取舍：
 * <ol>
 *   <li>不再需要 {@link com.shiyi.domain.agent.model.valobj.dynamic.AgentExecutionContext} 透传 ThreadLocal，
 *       终端会话直接从图 state 传入；</li>
 *   <li>SSH 命令级串行化在 {@link GraphSshToolService} 内部完成，因此子 Agent 可以并行"思考"，
 *       只有真正下发命令的时刻才串行，不会出现两个子 Agent 的输出互相串台。</li>
 * </ol>
 * 刻意不做"超时强杀"：子图执行到一半被中断会留下不完整的 state，
 * 这里只记录每个任务的耗时，超时与否交给父 Agent 依据结果自行决策。
 *
 * @author shiyi
 */
@Service
public class GraphOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(GraphOrchestrator.class);

    /** 默认并发度：默认值保持一致。 */
    public static final int DEFAULT_MAX_CONCURRENCY = 4;

    /** 单个子任务结果回填给父 Agent 的最大长度，避免一次批量派发撑爆上下文。 */
    private static final int SUB_RESULT_MAX_CHARS = 1500;

    /** 一次批量派发最多接受的任务数，防止模型一口气规划出几十个任务。 */
    public static final int MAX_TASKS = 8;

    /** 子图缺省 token 预算：调用方没传父级剩余额度时的兜底上限。 */
    private static final int DEFAULT_CHILD_TOKEN_BUDGET = 24000;

    private final GraphDispatchService dispatchService;
    private final Executor executor;

    /**
     * 显式注入业务线程池：子图并发必须与 HTTP/框架默认线程池隔离，
     * 否则一次批量派发就会把 Web 线程占满。
     */
    public GraphOrchestrator(GraphDispatchService dispatchService,
                             @Qualifier("threadPoolExecutor") Executor executor) {
        this.dispatchService = dispatchService;
        this.executor = executor;
    }

    /**
     * 执行一批子任务并返回给父 Agent 的汇总文本（永不抛异常）。
     *
     * @param sessionId         父会话 id
     * @param userId            用户 id
     * @param terminalSessionId 父会话绑定的 SSH 终端会话 id
     * @param depth             父 Agent 当前的派发深度
     * @param specs             任务列表（含 dependsOn 依赖）
     * @param maxConcurrency    并发上限，null 时取 {@link #DEFAULT_MAX_CONCURRENCY}
     * @param failFast          首个失败后是否停止调度，null 视为 false
     */
    public String executeBatch(String sessionId, String userId, String terminalSessionId, int depth,
                               List<BatchDispatchRequest.TaskSpec> specs,
                               Integer maxConcurrency, Boolean failFast) {
        return executeBatchDetailed(sessionId, userId, terminalSessionId, depth, TraceContext.current(),
                specs, maxConcurrency, failFast).text();
    }

    /** 带追踪 ID 的批量执行：并发子任务会各自继承这个 trace-id，日志可按 id 聚合。 */
    public String executeBatch(String sessionId, String userId, String terminalSessionId, int depth,
                               String traceId, List<BatchDispatchRequest.TaskSpec> specs,
                               Integer maxConcurrency, Boolean failFast) {
        return executeBatchDetailed(sessionId, userId, terminalSessionId, depth, traceId,
                specs, maxConcurrency, failFast).text();
    }

    /**
     * 批量执行的带账版本：除了给模型看的汇总文本，还带回本批子图一共花掉多少 token。
     * <p>
     * 为什么要把这个数字单独带出来：批量派发最多 8 个子 Agent 并发跑，每个子图都有
     * 自己的 maxTotalTokens 预算。如果父级账上看不见子级消耗，一次批量派发就能把总成本
     * 翻好几倍而父级预算毫无反应——预算是分层算的，必须逐层上报。
     */
    public BatchResult executeBatchDetailed(String sessionId, String userId, String terminalSessionId, int depth,
                                            List<BatchDispatchRequest.TaskSpec> specs,
                                            Integer maxConcurrency, Boolean failFast) {
        return executeBatchDetailed(sessionId, userId, terminalSessionId, depth, TraceContext.current(),
                specs, maxConcurrency, failFast);
    }

    public BatchResult executeBatchDetailed(String sessionId, String userId, String terminalSessionId, int depth,
                                            String traceId,
                                            List<BatchDispatchRequest.TaskSpec> specs,
                                            Integer maxConcurrency, Boolean failFast) {
        return executeBatchDetailed(sessionId, userId, terminalSessionId, depth, traceId,
                specs, maxConcurrency, failFast, "", DEFAULT_CHILD_TOKEN_BUDGET);
    }

    /**
     * 带主机维度与预算的批量执行。
     * <p>
     * 两个参数都要往下传给每个子图：hostKey 决定子图里长期记忆写/读的分组（不传就是 USER 级，
     * 主机维度当场丢失）；tokenBudget 是父级剩余额度，作为每个子图的上限——批量最多并发 8 个子图，
     * 只做事后记账的话，一次派发就能把父级预算撑爆好几倍。
     */
    public BatchResult executeBatchDetailed(String sessionId, String userId, String terminalSessionId, int depth,
                                            String traceId,
                                            List<BatchDispatchRequest.TaskSpec> specs,
                                            Integer maxConcurrency, Boolean failFast,
                                            String hostKey, int tokenBudget) {
        return executeBatchDetailed(sessionId, userId, terminalSessionId, depth, traceId,
                specs, maxConcurrency, failFast, hostKey, tokenBudget, null);
    }

    /**
     * 带事件出口的批量执行：把每个子任务的派发进度与工具事件转发到父请求 SSE。
     * <p>
     * 批量最多 8 个子图并发，父流在等待期间没有任何输出；不转发的话前端只看到"卡住"，
     * 也看不到子智能体究竟在目标机上跑了什么。转发出去的事件都带 {@code agent} 标记，
     * 前端据此分组渲染，不会把 8 路混成一坨。
     *
     * @param eventSink 父请求的流式桥，可为 null（不转发）
     */
    public BatchResult executeBatchDetailed(String sessionId, String userId, String terminalSessionId, int depth,
                                            String traceId,
                                            List<BatchDispatchRequest.TaskSpec> specs,
                                            Integer maxConcurrency, Boolean failFast,
                                            String hostKey, int tokenBudget,
                                            TokenStreamBridge eventSink) {
        if (specs == null || specs.isEmpty()) {
            return new BatchResult("（批量派发失败：任务列表为空）", 0);
        }
        if (specs.size() > MAX_TASKS) {
            return new BatchResult("（批量派发失败：一次最多派发 " + MAX_TASKS
                    + " 个任务，收到 " + specs.size() + " 个）", 0);
        }
        // 并发写，必须用原子量；子任务之间互不依赖，累加顺序无所谓。
        java.util.concurrent.atomic.AtomicInteger batchTokens = new java.util.concurrent.atomic.AtomicInteger();

        // ---- 1. 规格 → DynamicTask，顺带补全缺失的 taskId ----
        List<DynamicTask> taskList = new ArrayList<>();
        Map<String, DynamicTask> index = new LinkedHashMap<>();
        int autoIndex = 0;
        for (BatchDispatchRequest.TaskSpec spec : specs) {
            if (spec == null || spec.agentName() == null || spec.agentName().isBlank()) {
                return new BatchResult("（批量派发失败：存在没有指定 agentName 的任务）", 0);
            }
            String taskId = (spec.taskId() == null || spec.taskId().isBlank())
                    ? "task-" + (++autoIndex) : spec.taskId();
            if (index.containsKey(taskId)) {
                return new BatchResult("（批量派发失败：taskId 重复 - " + taskId + "）", 0);
            }
            DynamicTask task = DynamicTask.builder()
                    .taskId(taskId)
                    .agentName(spec.agentName())
                    .request(spec.request())
                    .dependsOn(spec.dependsOn() == null ? new ArrayList<>() : new ArrayList<>(spec.dependsOn()))
                    .build();
            index.put(taskId, task);
            taskList.add(task);
        }

        int concurrency = Math.max(1, maxConcurrency == null ? DEFAULT_MAX_CONCURRENCY : maxConcurrency);
        boolean fastFail = Boolean.TRUE.equals(failFast);

        log.info("[batch] 开始批量派发，任务数={}, 并发={}, failFast={}, 终端会话={}",
                taskList.size(), concurrency, fastFail, terminalSessionId);

        // ---- 2. DAG 调度循环 ----
        int round = 0;
        while (taskList.stream().anyMatch(task -> task.getStatus() == TaskStatus.PENDING)) {
            round++;

            // failFast：已有任务失败 → 剩余 PENDING 全部 SKIP 并退出
            if (fastFail && anyFailed(taskList)) {
                skipPending(taskList, "skipped by failFast: another task failed");
                break;
            }

            // 级联跳过：依赖了 FAILED / SKIPPED 的任务显式 SKIP（逐轮传播到整条依赖链）
            taskList.stream()
                    .filter(task -> task.getStatus() == TaskStatus.PENDING)
                    .filter(task -> task.getDependsOn().stream()
                            .map(index::get)
                            .anyMatch(parent -> parent == null
                                    || parent.getStatus() == TaskStatus.FAILED
                                    || parent.getStatus() == TaskStatus.SKIPPED))
                    .forEach(task -> {
                        task.setStatus(TaskStatus.SKIPPED);
                        task.setError("skipped: dependency failed or skipped");
                    });

            // 就绪：PENDING 且所有依赖都 COMPLETED
            List<DynamicTask> readyTasks = taskList.stream()
                    .filter(task -> task.getStatus() == TaskStatus.PENDING)
                    .filter(task -> task.getDependsOn().stream()
                            .map(index::get)
                            .allMatch(parent -> parent != null && parent.getStatus() == TaskStatus.COMPLETED))
                    .toList();

            // 无就绪任务：依赖关系无法满足（环），提前退出，剩余任务保持 PENDING 并在汇总里体现
            if (readyTasks.isEmpty()) {
                log.warn("[batch] 第 {} 轮无就绪任务，疑似依赖成环，提前结束", round);
                break;
            }

            // 信号量补位：所有就绪任务一次性交给池，但"真正在跑"的永远不超过 concurrency 个。
            // 旧实现按 concurrency 切片、片内 allOf().join() 等齐再发下一片，慢任务会拖住整片
            // （8 个任务要等两片，每片时长由最慢的那个决定，其余槽位全程空转）。
            // 换成"拿到槽位就跑、跑完立刻让位"之后，快任务不再陪跑，慢任务只占自己那一个槽位。
            boolean stop = false;
            java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(concurrency);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (DynamicTask task : readyTasks) {
                // failFast 在提交前检查：已失败就不再往池里塞新任务。
                // 已提交但还没拿到槽位的任务无法撤回，会照常跑完（这是补位模式与切片模式的取舍）。
                if (fastFail && anyFailed(taskList)) {
                    skipPending(taskList, "skipped by failFast: another task failed");
                    stop = true;
                    break;
                }
                futures.add(CompletableFuture.runAsync(
                        // wrap：MDC 是 ThreadLocal，线程池里的子任务不会自动继承父线程的 trace-id，
                        // 不包一层的话，并发派发出去的子智能体日志里 trace 是空的。
                        TraceContext.wrap(() -> {
                            try {
                                slots.acquire();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                task.setStatus(TaskStatus.SKIPPED);
                                task.setError("interrupted while waiting for a dispatch slot");
                                return;
                            }
                            try {
                                runOne(task, sessionId, userId, terminalSessionId,
                                        depth, traceId, hostKey, tokenBudget, batchTokens, eventSink);
                            } finally {
                                slots.release();
                            }
                        }), executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            if (stop) {
                break;
            }
            log.info("[batch] 第 {} 轮完成，就绪 {} 个", round, readyTasks.size());
        }

        return new BatchResult(render(taskList), batchTokens.get());
    }

    /**
     * 执行单个子任务：调派发服务跑子图，结果写回 task。
     * <p>
     * 派发服务返回结构化信封（{@link DispatchResult}），这里按 {@code status} 判成败：
     * 用文本前缀判断是这个类上一版的做法，信封一改就会静默失配（把失败当成功），
     * 所以状态必须从字段读，不能从措辞猜。
     */
    private void runOne(DynamicTask task, String sessionId, String userId,
                        String terminalSessionId, int depth, String traceId,
                        String hostKey, int tokenBudget,
                        java.util.concurrent.atomic.AtomicInteger batchTokens,
                        TokenStreamBridge eventSink) {
        task.setStatus(TaskStatus.RUNNING);
        task.setAttempts(1);
        long start = System.currentTimeMillis();
        try {
            DispatchResult result = dispatchService.dispatchDetailed(task.getAgentName(), task.getRequest(),
                    sessionId, userId, terminalSessionId, depth, traceId, hostKey, tokenBudget, eventSink);
            batchTokens.addAndGet(result.tokens());
            if (DispatchResult.STATUS_OK.equals(result.status())) {
                task.setStatus(TaskStatus.COMPLETED);
                task.setResult(result.summary() == null ? "" : result.summary());
            } else {
                // empty / error 都不能算完成：empty 是跑完没结论，父模型看到 COMPLETED
                // 会把空结论当成任务已交付，下游 dependsOn 的任务也会继续跑。
                task.setStatus(TaskStatus.FAILED);
                task.setError(result.summary() == null || result.summary().isBlank()
                        ? "子智能体未给出结论（status=" + result.status() + "）"
                        : result.summary());
            }
        } catch (Exception e) {
            task.setStatus(TaskStatus.FAILED);
            task.setError("派发异常：" + e.getMessage());
            log.warn("[batch] 任务 {} 执行异常", task.getTaskId(), e);
        } finally {
            log.info("[batch] 任务 {}（{}）结束，状态={}，耗时={}ms", task.getTaskId(),
                    task.getAgentName(), task.getStatus(), System.currentTimeMillis() - start);
        }
    }

    /** 是否已有任务失败。 */
    private boolean anyFailed(List<DynamicTask> taskList) {
        return taskList.stream().anyMatch(task -> task.getStatus() == TaskStatus.FAILED);
    }

    /** 把剩余 PENDING 任务统一标记为 SKIPPED。 */
    private void skipPending(List<DynamicTask> taskList, String reason) {
        taskList.stream()
                .filter(task -> task.getStatus() == TaskStatus.PENDING)
                .forEach(task -> {
                    task.setStatus(TaskStatus.SKIPPED);
                    task.setError(reason);
                });
    }

    /** 汇总成父 Agent 可读的文本。 */
    private String render(List<DynamicTask> tasks) {
        long completed = tasks.stream().filter(task -> task.getStatus() == TaskStatus.COMPLETED).count();
        StringBuilder sb = new StringBuilder();
        sb.append("【批量派发结果】").append(completed).append("/").append(tasks.size()).append(" 成功\n");
        for (DynamicTask task : tasks) {
            sb.append("- ").append(task.getTaskId()).append("（").append(task.getAgentName()).append("）")
                    .append(statusText(task.getStatus())).append("：");
            if (task.getStatus() == TaskStatus.COMPLETED) {
                sb.append(truncate(task.getResult()));
            } else if (task.getStatus() == TaskStatus.FAILED) {
                sb.append(truncate(task.getError()));
            } else {
                sb.append("（未执行：上游依赖未完成或失败）");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String statusText(TaskStatus status) {
        return switch (status) {
            case COMPLETED -> "成功";
            case FAILED -> "失败";
            case SKIPPED -> "已跳过";
            default -> "未执行";
        };
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() <= SUB_RESULT_MAX_CHARS
                ? trimmed : trimmed.substring(0, SUB_RESULT_MAX_CHARS) + "\n...[子任务结果已截断]";
    }

    /**
     * 批量执行结果：{@code text} 回填给模型，{@code tokens} 是子图消耗总量（父级记账用）。
     */
    public record BatchResult(String text, int tokens) {
    }
}