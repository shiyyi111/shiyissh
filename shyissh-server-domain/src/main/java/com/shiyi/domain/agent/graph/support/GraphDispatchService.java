package com.shiyi.domain.agent.graph.support;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.shiyi.domain.agent.graph.GraphStateKeys;
import com.shiyi.domain.agent.graph.SshAgentGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 子智能体派发服务（图的编排能力）。
 * <p>
 * 派发链路：
 * <pre>
 *   父 Agent 的工具列表里挂 dispatchSubAgent（见 {@code LlmNode#buildToolCallbacks}）
 *           → 工具内部 runner.runAsync(subAgent, ...) 跑子 Agent 的 ReAct 循环
 *   SAA 版：[llm] 挂 dispatchSubAgent 工具
 *           → 工具内部用同一张图、换 AGENT_NAME + 新 threadId 再跑一遍
 * </pre>
 * 为什么复用同一张图而不是新建：节点、工具、守卫、观测逻辑完全一样，
 * 差别只在 system instruction 和可用工具集（都由 AGENT_NAME 决定），
 * 再造一张图只会多一份要维护的代码。
 * <p>
 * 防无限递归：depth 每层 +1，超过 {@link #MAX_DEPTH} 直接返回提示文本。
 *
 * @author shiyi
 */
@Service
public class GraphDispatchService {

    private static final Logger log = LoggerFactory.getLogger(GraphDispatchService.class);

    /** 最大派发层数；1 层已够用（父 → 子），再深收益很低且容易失控。 */
    public static final int MAX_DEPTH = 1;

    /** 子 Agent 的轮次预算，比父 Agent 略小（子任务是收敛型任务）。 */
    private static final int SUB_AGENT_MAX_ROUNDS = 3;

    /** 子图缺省 token 预算：调用方没给父级剩余额度时，子图仍要有一个上限。 */
    private static final int DEFAULT_CHILD_TOKEN_BUDGET = 24000;

    /** 失败原因前缀：超时既要能被信封文案识别，也要能让 stopReason 落成 timeout。 */
    private static final String FAILURE_TIMEOUT = "超时";

    /**
     * 一次派发的整体超时（含子 Agent 自己的多轮 ReAct 与工具执行）。
     * <p>
     * 没有超时的时候，子图卡在一条不返回的 SSH 命令上，父请求会一直挂着——
     * 父级的 max-rounds / token 闸都只在节点边界生效，拦不住"永远到不了下一个节点"。
     */
    @Value("${app.agent.dispatch.timeout-ms:120000}")
    private long childTimeoutMs = 120_000L;

    /** 执行失败后的重试次数（不含首次）。0 = 不重试。 */
    @Value("${app.agent.dispatch.max-retries:2}")
    private int maxRetries = 2;

    /** 退避基数：第 n 次重试前等待 backoff * 2^(n-1)，再被 max-backoff-ms 封顶。 */
    @Value("${app.agent.dispatch.retry-backoff-ms:1000}")
    private long retryBackoffMs = 1_000L;

    /** 退避上限：避免指数增长把父请求拖成分钟级等待。 */
    @Value("${app.agent.dispatch.max-backoff-ms:30000}")
    private long maxBackoffMs = 30_000L;

    /** 用 ObjectProvider 延迟解析，避免与 SshAgentGraph 形成构造期循环依赖。 */
    @Resource
    private ObjectProvider<SshAgentGraph> sshAgentGraphProvider;

    @Resource
    private GraphAgentRegistry agentRegistry;

    private final AtomicInteger dispatchCounter = new AtomicInteger();

    /**
     * 派发一个子任务给指定子 Agent，阻塞等待它的最终回答，返回**给模型看的 JSON 信封**。
     * <p>
     * 工具调用方（{@code dispatchSubAgent}）拿到的是字符串，所以这里再包一层；
     * 需要拿结构化结果（比如把子图 token 记进父账）的调用方用 {@link #dispatchDetailed}。
     *
     * @param agentName         子 Agent 名称（yml agents[].name）
     * @param task              子任务描述
     * @param sessionId         父会话 id（子会话会派生出独立 id，避免历史串台）
     * @param userId            用户 id
     * @param terminalSessionId 终端会话 id（子 Agent 共用同一个 SSH 终端）
     * @param depth             当前派发深度
     * @return 见 {@link DispatchResult#toEnvelope()}
     */
    public String dispatch(String agentName, String task, String sessionId, String userId,
                           String terminalSessionId, int depth) {
        return dispatch(agentName, task, sessionId, userId, terminalSessionId, depth, TraceContext.current());
    }

    /** 带追踪 ID 的版本：子智能体日志与父请求共用同一个 trace-id，便于按 id 捞整条链路。 */
    public String dispatch(String agentName, String task, String sessionId, String userId,
                           String terminalSessionId, int depth, String traceId) {
        return dispatchDetailed(agentName, task, sessionId, userId, terminalSessionId, depth, traceId)
                .toEnvelope();
    }

    /**
     * 派发的结构化形态：失败也返回 {@link DispatchResult}，绝不抛异常进图。
     * <p>
     * 失败一律用 {@link DispatchResult#STATUS_ERROR}：父模型看到 error 应当换策略或直接作答，
     * 而不是反复重试同一个派发（重试多少次都会得到同样的拒绝原因）。
     */
    public DispatchResult dispatchDetailed(String agentName, String task, String sessionId, String userId,
                                           String terminalSessionId, int depth) {
        return dispatchDetailed(agentName, task, sessionId, userId, terminalSessionId, depth,
                TraceContext.current());
    }

    /**
     * 带追踪 ID 的派发：子图节点、子智能体工具调用的日志都会带上同一个 trace-id。
     */
    public DispatchResult dispatchDetailed(String agentName, String task, String sessionId, String userId,
                                           String terminalSessionId, int depth, String traceId) {
        return dispatchDetailed(agentName, task, sessionId, userId, terminalSessionId, depth, traceId,
                "", DEFAULT_CHILD_TOKEN_BUDGET);
    }

    /**
     * 带主机维度与 token 预算的派发。
     * <p>
     * 为什么要把 hostKey 传下去：子图跑的是同一条链路，长期记忆的写入与召回都按 hostKey 分组。
     * 父级不传，子图里取到的就是空串，记忆会静默退化成 USER 级——写进去的那一刻主机维度就丢了，
     * 事后无法从数据里补回来。
     * <p>
     * 为什么要把预算传下去：子图自带一份 24000 的默认额度，父级只在事后把子图用量加进总账，
     * 那是"记账"不是"设限"——派发一次就能花掉一整份预算，父级的闸门根本来不及拦。
     * 这里把父级剩余额度当作子图上限，把闸门前移到子图入口。
     */
    public DispatchResult dispatchDetailed(String agentName, String task, String sessionId, String userId,
                                           String terminalSessionId, int depth, String traceId,
                                           String hostKey, int tokenBudget) {
        return dispatchDetailed(agentName, task, sessionId, userId, terminalSessionId, depth, traceId,
                hostKey, tokenBudget, null);
    }

    /**
     * 带事件出口的派发：把子智能体的过程转发到父请求的 SSE。
     * <p>
     * 为什么需要：派发是<b>同步阻塞</b>的，父图在等子图跑完这段时间里一个 token 都不产出，
     * 前端只看 token 流会以为请求卡死；而子智能体在目标机上执行了什么命令，父流里更是完全看不见。
     * 这里补两类事件（都带 {@code agent} 与 {@code depth} 字段，前端按 agent 分组渲染）：
     * <ul>
     *   <li>{@code dispatch_start} / {@code dispatch_end}：派发进度（谁、什么任务、结果如何、耗时多久）；</li>
     *   <li>子图的 {@code tool_call} / {@code tool_result}：原样转发，只加 agent 标记。</li>
     * </ul>
     * 只转发<b>结构化事件</b>，不转发子智能体的 token 文本——子 Agent 的输出多是内部推理，
     * 并发派发时多路 token 交错反而会让前端更乱。
     *
     * @param eventSink 父请求的流式桥；为 null 时退化成"不转发"（单测、非流式调用）
     */
    public DispatchResult dispatchDetailed(String agentName, String task, String sessionId, String userId,
                                           String terminalSessionId, int depth, String traceId,
                                           String hostKey, int tokenBudget, TokenStreamBridge eventSink) {
        if (task == null || task.isBlank()) {
            return DispatchResult.failed(agentName, "（派发失败：子任务描述为空）");
        }
        if (depth >= MAX_DEPTH) {
            log.warn("[dispatch] 派发深度超限 depth={}，拒绝派发 {}", depth, agentName);
            return DispatchResult.failed(agentName,
                    "（派发失败：已达到最大派发层级 " + MAX_DEPTH + "，请直接完成任务）");
        }
        if (agentRegistry != null && agentRegistry.find(agentName) == null) {
            return DispatchResult.failed(agentName, "（派发失败：未找到名为 " + agentName + " 的智能体）");
        }
        SshAgentGraph graph = sshAgentGraphProvider == null ? null : sshAgentGraphProvider.getIfAvailable();
        if (graph == null) {
            return DispatchResult.failed(agentName, "（派发失败：图未初始化）");
        }

        String childSessionId = sessionId + "#sub-" + agentName + "-" + dispatchCounter.incrementAndGet();
        String childTraceId = TraceContext.continueOrCreate(traceId);
        // 先告诉前端"开始派发了"：之后父流会静默等待子图跑完，没有这条事件界面就是卡死的样子。
        emitDispatchStart(eventSink, agentName, task, depth);

        int maxAttempts = 1 + Math.max(0, maxRetries);
        int attempts = 0;
        NodeOutput lastOutput = null;
        List<String> tokens = new CopyOnWriteArrayList<>();
        String failureReason = null;

        long start = System.currentTimeMillis();
        // 子图是在调用线程上同步跑的，把 trace-id 绑到线程上，子智能体（含它调的工具）的日志
        // 才会和父请求用同一个 id；跑完还原，避免影响批量派发里同一线程上的其它任务。
        String parentTraceId = TraceContext.current();
        TraceContext.put(childTraceId);
        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                attempts = attempt;
                // 每次尝试用独立 threadId：重试要重跑，不能挂在上一轮的检查点上续跑
                // （续跑会带着上次的半截状态，比重新跑更不可控）。
                String attemptSessionId = attempt == 1 ? childSessionId : childSessionId + "#try" + attempt;
                TokenStreamBridge bridge = new TokenStreamBridge();
                List<String> attemptTokens = new CopyOnWriteArrayList<>();
                bridge.tokens().subscribe(attemptTokens::add);
                // 子图在目标机上跑了什么命令，必须让父请求的前端看得见：把子桥的结构化事件
                // 实时转发进父桥（只加 agent/depth 标记，不改事件体）。
                Disposable eventSubscription = forwardChildEvents(bridge, eventSink, agentName, depth);
                RunnableConfig config = RunnableConfig.builder()
                        .threadId(attemptSessionId)
                        .addMetadata("tokenSink", bridge)
                        .build();
                try {
                    lastOutput = graph.stream(buildChildInput(agentName, task, attemptSessionId, userId,
                                    terminalSessionId, childTraceId, hostKey, tokenBudget, depth), config)
                            // 超时放在 Flux 上，而不是靠 future 的 get(timeout)：
                            // 超时会取消订阅，子图不再继续往下跑节点。
                            .timeout(Duration.ofMillis(Math.max(1L, childTimeoutMs)))
                            .blockLast();
                    tokens = attemptTokens;
                    failureReason = null;
                    if (attempt > 1) {
                        log.info("[dispatch] 子 Agent {} 第 {}/{} 次尝试成功", agentName, attempt, maxAttempts);
                    }
                    break;
                } catch (Exception e) {
                    failureReason = classifyFailure(e);
                    if (failureReason != null && failureReason.startsWith(FAILURE_TIMEOUT)) {
                        // 超时不重试：子图可能已经跑了一半（写操作尤其危险），
                        // 重跑等于让同一条变更执行两三次；交给父模型决定怎么重派。
                        log.warn("[dispatch] 子 Agent {} 第 {}/{} 次尝试超时（{}），不再重试",
                                agentName, attempt, maxAttempts, failureReason);
                        break;
                    }
                    if (attempt >= maxAttempts) {
                        break;
                    }
                    long waitMs = backoffMs(attempt);
                    log.warn("[dispatch] 子 Agent {} 第 {}/{} 次尝试失败（{}），{}ms 后重试",
                            agentName, attempt, maxAttempts, failureReason, waitMs);
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException ie) {
                        // 被中断说明父请求已经没人要了：不再重试，把中断标志还回去交给上层。
                        Thread.currentThread().interrupt();
                        failureReason = failureReason + "（重试被中断）";
                        break;
                    }
                } finally {
                    // 每次尝试一个子桥：转发订阅要随这次尝试一起结束，否则重试会叠出多份重复事件。
                    if (eventSubscription != null) {
                        eventSubscription.dispose();
                    }
                }
            }
        } finally {
            if (parentTraceId.isBlank()) {
                TraceContext.clear();
            } else {
                TraceContext.put(parentTraceId);
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        // 注意区分两种"没有输出"：抛过异常才是失败；图正常结束却没产出（Flux 为空）走下面的
        // STATUS_EMPTY —— 那是"跑了但没结论"，父模型可以换问法重派，性质与失败不同。
        if (lastOutput == null && failureReason != null) {
            boolean timeout = failureReason != null && failureReason.startsWith(FAILURE_TIMEOUT);
            String retried = attempts > 1 ? "，已尝试 " + attempts + " 次" : "";
            log.warn("[dispatch] 子 Agent {} 派发失败（{}，耗时 {}ms{}）",
                    agentName, failureReason, elapsed, retried);
            DispatchResult failed = new DispatchResult(agentName, DispatchResult.STATUS_ERROR,
                    "（派发失败：" + (failureReason == null ? "子智能体未返回任何节点输出" : failureReason)
                            + retried + "）",
                    timeout ? "timeout" : "error", elapsed, 0, attempts);
            emitDispatchEnd(eventSink, agentName, depth, failed);
            return failed;
        }

        String answer = String.join("", tokens);
        String stopReason = stopReasonOf(lastOutput);
        int childTokens = totalTokensOf(lastOutput);
        log.info("[dispatch] 子 Agent={}, 会话={}, 耗时={}ms, 尝试={}, 回答长度={}, 用量={}, 收口={}",
                agentName, childSessionId, elapsed, attempts, answer.length(), childTokens, stopReason);
        DispatchResult result;
        if (answer.isBlank()) {
            result = new DispatchResult(agentName, DispatchResult.STATUS_EMPTY,
                    "（子智能体未给出结论）", stopReason, elapsed, childTokens, attempts);
        } else {
            result = new DispatchResult(agentName, DispatchResult.STATUS_OK, answer, stopReason, elapsed, childTokens,
                    attempts);
        }
        emitDispatchEnd(eventSink, agentName, depth, result);
        return result;
    }

    /**
     * 把子桥的结构化事件转发进父桥，并打上 {@code agent}/{@code depth} 标记。
     * <p>
     * 标记不是装饰：批量派发时最多 8 个子图并发往同一条父流里塞事件，
     * 前端只有拿到 agent 才能把它们分到各自的任务卡片下。
     *
     * @return 订阅句柄（用完必须 dispose，否则重试会叠出重复事件）；不转发时返回 null
     */
    private Disposable forwardChildEvents(TokenStreamBridge child, TokenStreamBridge parent,
                                          String agentName, int depth) {
        if (child == null || parent == null) {
            return null;
        }
        return child.events().subscribe(event -> parent.emitEvent(tagChildEvent(event, agentName, depth)));
    }

    /** 子事件加标记：只加字段，不改原事件类型与内容。 */
    private Map<String, Object> tagChildEvent(Map<String, Object> event, String agentName, int depth) {
        Map<String, Object> tagged = new LinkedHashMap<>(event == null ? Map.of() : event);
        tagged.put("agent", agentName);
        tagged.put("depth", depth + 1);
        return tagged;
    }

    /** 派发开始事件：前端据此把"等待中"渲染成"sshDiagnosisAgent 正在跑"。 */
    private void emitDispatchStart(TokenStreamBridge sink, String agentName, String task, int depth) {
        if (sink == null) {
            return;
        }
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event", GraphStateKeys.EVENT_DISPATCH_START);
            event.put("type", GraphStateKeys.EVENT_DISPATCH_START);
            event.put("agent", agentName);
            event.put("depth", depth + 1);
            event.put("task", brief(task, 200));
            sink.emitEvent(event);
        } catch (Exception e) {
            log.warn("[dispatch] 发送 dispatch_start 事件失败: {}", e.getMessage());
        }
    }

    /** 派发结束事件：结果、耗时、尝试次数、子图用量一次带回去，前端不用再猜。 */
    private void emitDispatchEnd(TokenStreamBridge sink, String agentName, int depth, DispatchResult result) {
        if (sink == null || result == null) {
            return;
        }
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event", GraphStateKeys.EVENT_DISPATCH_END);
            event.put("type", GraphStateKeys.EVENT_DISPATCH_END);
            event.put("agent", agentName);
            event.put("depth", depth + 1);
            event.put("status", result.status());
            event.put("stopReason", result.stopReason());
            event.put("elapsedMs", result.elapsedMs());
            event.put("tokens", result.tokens());
            event.put("attempts", result.attempts());
            sink.emitEvent(event);
        } catch (Exception e) {
            log.warn("[dispatch] 发送 dispatch_end 事件失败: {}", e.getMessage());
        }
    }

    /** 截断长文本：事件只需要让用户认得出"在干什么"，不需要整段任务描述。 */
    private String brief(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
    }

    /** 子图入参：每次尝试重建一份，attemptSessionId 变了它也得跟着变。 */
    private Map<String, Object> buildChildInput(String agentName, String task, String attemptSessionId,
                                                String userId, String terminalSessionId, String childTraceId,
                                                String hostKey, int tokenBudget, int depth) {
        Map<String, Object> input = new HashMap<>();
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(task));
        input.put(GraphStateKeys.MESSAGES, messages);
        input.put(GraphStateKeys.SESSION_ID, attemptSessionId);
        input.put(GraphStateKeys.USER_ID, userId == null ? "default" : userId);
        input.put(GraphStateKeys.TERMINAL_SESSION_ID, terminalSessionId == null ? "" : terminalSessionId);
        input.put(GraphStateKeys.AGENT_NAME, agentName);
        input.put(GraphStateKeys.TRACE_ID, childTraceId);
        // 主机维度必须继承父级：子图里没有终端会话 id 之外的线索，不传就是空串，记忆会退化成 USER 级。
        input.put(GraphStateKeys.HOST_KEY, hostKey == null ? "" : hostKey);
        // 预算前移：子图上限取父级剩余额度，而不是各自再领一份 24000。
        input.put(GraphStateKeys.MAX_TOTAL_TOKENS,
                tokenBudget > 0 ? tokenBudget : DEFAULT_CHILD_TOKEN_BUDGET);
        input.put(GraphStateKeys.DEPTH, depth + 1);
        input.put(GraphStateKeys.ROUND, 0);
        input.put(GraphStateKeys.MAX_ROUNDS, SUB_AGENT_MAX_ROUNDS);
        input.put(GraphStateKeys.ANSWER_STREAMED, false);
        return input;
    }

    /** 失败原因的统一措辞：超时与异常要能区分，前者值得重试，后者要看具体异常。 */
    private String classifyFailure(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root instanceof TimeoutException) {
            return FAILURE_TIMEOUT + "（超过 " + childTimeoutMs + "ms 未返回）";
        }
        String message = root.getMessage();
        return (message == null || message.isBlank())
                ? "异常：" + root.getClass().getSimpleName()
                : "异常：" + message;
    }

    /** 指数退避：第 n 次重试等 backoff * 2^(n-1)，封顶 maxBackoffMs。 */
    private long backoffMs(int attempt) {
        long base = Math.max(0L, retryBackoffMs);
        if (base == 0L) {
            return 0L;
        }
        int shift = Math.min(Math.max(0, attempt - 1), 10);
        long wait = base << shift;
        return maxBackoffMs > 0 ? Math.min(wait, maxBackoffMs) : wait;
    }

    /**
     * 从子图最后一个节点输出里取收口原因。
     * <p>
     * 取不到就按 completed 处理：调用方拿到的信封里这个字段不能为空，
     * 否则父模型又得猜"是答完了还是被截断了"。
     */
    private String stopReasonOf(NodeOutput output) {
        if (output == null || output.state() == null) {
            return "completed";
        }
        String stopReason = output.state().value(GraphStateKeys.STOP_REASON, "");
        return stopReason == null || stopReason.isBlank() ? "completed" : stopReason;
    }

    /** 子图本次实际消耗的 token（父账要能看见子账，否则并发派发时预算形同虚设）。 */
    private int totalTokensOf(NodeOutput output) {
        if (output == null || output.state() == null) {
            return 0;
        }
        Integer total = output.state().value(GraphStateKeys.TOTAL_TOKENS, 0);
        return total == null || total < 0 ? 0 : total;
    }
}