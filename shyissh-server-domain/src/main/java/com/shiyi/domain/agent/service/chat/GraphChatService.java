package com.shiyi.domain.agent.service.chat;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.fastjson2.JSON;
import com.shiyi.domain.agent.graph.GraphStateKeys;
import com.shiyi.domain.agent.graph.SshAgentGraph;
import com.shiyi.domain.agent.graph.support.GraphHistorySummarizer;
import com.shiyi.domain.agent.graph.support.GraphSession;
import com.shiyi.domain.agent.graph.support.GraphSshToolService;
import com.shiyi.domain.agent.graph.support.TokenStreamBridge;
import com.shiyi.domain.agent.graph.support.TraceContext;
import com.shiyi.domain.agent.service.IChatContextService;
import com.shiyi.domain.agent.service.ILongTermMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SAA 图链路的对话服务。
 * <p>
 * 一个请求进来后，各个环节由谁承担：
 * <pre>
 *   环节                                         承担者
 *   HTTP 入口                                    GraphChatService.handleMessageStream(...)
 *   对外输出                                      Flux&lt;String&gt;（一行一条已序列化的 SSE）
 *   驱动图执行                                    SshAgentGraph.stream()
 * </pre>
 * 对外仍是“一条一条往外发的事件”：{@code {"type":"node"|"text"|"done"|"error", ...}} 的小 JSON，
 * 前端按 type 分支渲染即可。
 *
 * @author shiyi
 */
@Service
public class GraphChatService {

    private static final Logger log = LoggerFactory.getLogger(GraphChatService.class);

    /** 单次请求默认最大模型调用轮数（防止模型反复调工具不收口）。 */
    @Value("${app.agent.max-rounds:3}")
    private int maxRounds = 3;

    /** 单次请求默认工具调用次数预算（跨轮累计）。 */
    private static final int DEFAULT_MAX_TOOL_CALLS = 20;

    /** 单次请求默认 token 预算（prompt + completion 累计）。 */
    private static final int DEFAULT_MAX_TOTAL_TOKENS = 24000;

    /** 工具次数预算，可配（app.agent.max-tool-calls）。 */
    @Value("${app.agent.max-tool-calls:20}")
    private int maxToolCalls;

    /** token 预算，可配（app.agent.max-total-tokens）。 */
    @Value("${app.agent.max-total-tokens:24000}")
    private int maxTotalTokens;

    @Resource
    private SshAgentGraph sshAgentGraph;

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    /** 上下文服务：提供 HistoryReducer 权重压缩（trimHistory）。 */
    @Resource
    private IChatContextService chatContextService;

    /**
     * SSH 工具服务：只用来反查"终端会话 → 主机标识"。
     * <p>
     * 这里不执行命令，只是借用它对会话的可见性；单独注入一个 {@link GraphSshToolService}
     * 而不是直接注入 {@code ISshTerminalService}，是为了让"主机标识怎么算"只有一处实现。
     */
    @Resource
    private GraphSshToolService sshToolService;

    /**
     * 会话摘要服务：把被三道闸丢掉的老消息压成几句话，并持久化成游标可复用的摘要。
     * <p>
     * 放在这里而不是节点里，是因为"裁多少"由 {@link GraphSession} 决定，
     * "裁掉的部分怎么补偿"必须紧挨着裁剪点，否则两边一旦不同步就会出现
     * "摘要了没丢的"或"丢了没摘要的"。
     * <p>
     * 与旧实现的区别：这里不再直接调 {@code GraphHistorySummarizer} 全量重算，
     * 而是交给 {@link SessionSummaryService} 按游标做增量合并（没有新滑出就不调模型）。
     */
    @Resource
    private SessionSummaryService sessionSummaryService;

    /**
     * 处理一次对话，返回 SSE 事件的字符串流。
     *
     * @param userId            用户 ID
     * @param sessionId         会话 ID（空则新建）
     * @param message           用户消息
     * @param terminalSessionId SSH 终端会话 ID（P2 真 SSH 用）
     * @return 每条都是一个 JSON 字符串，调用方拼 "\n" 后直接 send
     */
    public Flux<String> handleMessageStream(String userId, String sessionId, String message, String terminalSessionId) {
        return handleMessageStream(userId, sessionId, message, terminalSessionId, null);
    }

    /**
     * 处理一次对话（可指定入口 Agent）。
     *
     * @param agentName 入口 Agent 名称（yml agents[].name）；为空则用默认 Agent（第一个）
     */
    public Flux<String> handleMessageStream(String userId, String sessionId, String message,
                                            String terminalSessionId, String agentName) {
        return handleMessageStream(userId, sessionId, message, terminalSessionId, agentName, null);
    }

    /**
     * 处理一次对话（可指定入口 Agent，并可携带一条"已获人工批准"的命令）。
     *
     * @param approvedCommand 用户刚在审批接口里放行的命令原文；非空时 [guard] 会对
     *                        完全相同的命令直接判 PASS（按原文匹配，防止模型换命令搭便车）。
     *                        普通对话传 null 即可。
     */
    public Flux<String> handleMessageStream(String userId, String sessionId, String message,
                                            String terminalSessionId, String agentName,
                                            String approvedCommand) {
        String finalSessionId = (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString()
                : sessionId;
        // 追踪 ID：入口线程如果已经绑定了（Controller 从 X-Trace-Id 头解析过来），沿用同一个；
        // 否则新建一个。后面它同时进 MDC（日志）、state（业务/子智能体）、done 事件（前端）。
        String traceId = TraceContext.continueOrCreate(null);
        TraceContext.put(traceId);

        TokenStreamBridge bridge = new TokenStreamBridge();
        StringBuilder answerText = new StringBuilder();
        // 图跑完后用来读收口原因与轮数：Flux<NodeOutput> 每个元素都带当时的 state 快照，
        // 留最后一个即可（state 是累积的，最后一份就是终态）。
        AtomicReference<OverAllState> lastState = new AtomicReference<>();
        RunnableConfig config = RunnableConfig.builder()
                .threadId(finalSessionId)
                .addMetadata("tokenSink", bridge)
                .build();

        // 冷启动恢复：从 DB 载入最近 100 条历史，作为本次请求的候选上下文。
        // 装箱顺序 = HybridReducer 权重压缩（按 priority/新近挑）→ 6000 token → 12 轮 → 20 条。
        // 没有这一步，多轮对话每一轮都是"失忆"状态。
        GraphSession.LoadedHistory loaded = GraphSession.loadHistoryDetailed(
                longTermMemoryService, chatContextService, finalSessionId);
        List<Message> messages = new ArrayList<>();
        // 摘要只计算、不在这里拼消息：MESSAGES[0] 是 system instruction 的位置，只能有一个主人，
        // 两处都往那儿写就会互相覆盖（表现为"聊到第 N 轮人格丢了"）。
        // 这里把内容放进 state 键，由 [enrich] 在 instruction 之后统一拼装。
        String historySummary = sessionSummaryService == null
                ? ""
                : sessionSummaryService.summarize(finalSessionId, userId, loaded);
        // 摘要器返回 null = "该有摘要但要不到"（没模型/调用失败），返回空串 = "本来就不需要"。
        // 这个区别要下发到 done 事件里，否则排查"这轮为什么没摘要"只能翻日志。
        boolean summarySkipped = historySummary == null;
        messages.addAll(loaded.messages());
        messages.add(new UserMessage(message));

        Map<String, Object> input = new HashMap<>();
        input.put(GraphStateKeys.MESSAGES, messages);
        input.put(GraphStateKeys.HISTORY_SUMMARY, historySummary == null ? "" : historySummary);
        input.put(GraphStateKeys.SESSION_ID, finalSessionId);
        input.put(GraphStateKeys.USER_ID, userId == null ? "default" : userId);
        input.put(GraphStateKeys.TERMINAL_SESSION_ID, terminalSessionId);
        // 主机标识：由终端会话反查 SSH 连接 ID，供长期记忆的 HOST 作用域分组。
        // 入口解析一次、随 state 传递，节点不再各自反查（反查是 O(1) 但语义上一处更清晰）。
        input.put(GraphStateKeys.HOST_KEY, sshToolService == null ? "" : sshToolService.resolveHostKey(terminalSessionId));
        input.put(GraphStateKeys.AGENT_NAME, agentName == null ? "" : agentName);
        input.put(GraphStateKeys.TRACE_ID, traceId);
        input.put(GraphStateKeys.DEPTH, 0);
        input.put(GraphStateKeys.ROUND, 0);
        input.put(GraphStateKeys.MAX_ROUNDS, maxRounds);
        // 预算三项：轮数（上面）、工具调用次数、token 总量。
        // 轮数只能防"模型反复调工具"，防不住"一轮里并发调 8 个工具"和"上下文越滚越大"，
        // 因此下面两项是独立闸门，分别在 [llm] 与 [tool] 节点里生效。
        input.put(GraphStateKeys.MAX_TOOL_CALLS, maxToolCalls > 0 ? maxToolCalls : DEFAULT_MAX_TOOL_CALLS);
        input.put(GraphStateKeys.MAX_TOTAL_TOKENS, maxTotalTokens > 0 ? maxTotalTokens : DEFAULT_MAX_TOTAL_TOKENS);
        input.put(GraphStateKeys.TOOL_CALL_COUNT, 0);
        input.put(GraphStateKeys.PROMPT_TOKENS, 0);
        input.put(GraphStateKeys.COMPLETION_TOKENS, 0);
        input.put(GraphStateKeys.TOTAL_TOKENS, 0);
        // 人工审批放行：只有"这一次请求"带着它，且只有命令原文完全一致才生效
        input.put(GraphStateKeys.APPROVED_COMMAND, approvedCommand == null ? "" : approvedCommand.strip());
        input.put(GraphStateKeys.ANSWER_STREAMED, false);

        return renderStream(sshAgentGraph.stream(input, config), bridge, answerText, lastState, traceId,
                finalSessionId, summarySkipped);
    }

    /**
     * 是否具备"从审批断点续跑"的能力（容器里装配了检查点存储）。
     * <p>
     * 上层据此选择续跑还是退回"重新发起一次请求"，避免在没检查点的环境里把
     * 一次正常的审批变成一条 error 事件。
     */
    public boolean checkpointEnabled() {
        return sshAgentGraph.checkpointEnabled();
    }

    /**
     * 从人工审批断点继续执行。
     * <p>
     * 与"批准后重新发起一次请求"的差别：那条路要模型重新读一遍历史、重新生成一次工具调用，
     * 既慢又可能换一条命令；这里直接从检查点恢复，[approval] 节点拿到裁决后把 verdict 改成 PASS，
     * 流程沿同一条边进入 [tool] 执行<b>当初被拦下的那条命令</b>，然后继续 observe → llm，把结果交给模型收口。
     * <p>
     * 前提是容器里装配了检查点存储（{@code app.agent.checkpoint.enabled=true}），
     * 且上一次执行确实在 [approval] 前中断过；没有检查点时由上层退回"重新发起请求"的老做法。
     *
     * @param userId    用户 ID（日志/记忆用）
     * @param sessionId 会话 ID
     * @param threadId  产生这个审批单的那次执行的线程 ID（检查点以它为键）
     */
    public Flux<String> resumeApproval(String userId, String sessionId, String threadId) {
        String traceId = TraceContext.continueOrCreate(null);
        TraceContext.put(traceId);
        TokenStreamBridge bridge = new TokenStreamBridge();
        StringBuilder answerText = new StringBuilder();
        AtomicReference<OverAllState> lastState = new AtomicReference<>();

        if (!sshAgentGraph.checkpointEnabled()) {
            log.warn("[graph] 未启用检查点，无法续跑审批 session={} threadId={}", sessionId, threadId);
            return Flux.just(errorEvent("未启用检查点存储，无法从断点继续；请重新发起一次对话。"));
        }

        RunnableConfig config = RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata("tokenSink", bridge)
                .build();
        try {
            // 把裁决写回检查点里的 state：恢复时 [approval] 读它决定走 [tool] 还是收口
            sshAgentGraph.compiled().updateState(config, Map.of(
                    GraphStateKeys.APPROVAL_DECISION, GraphStateKeys.APPROVAL_APPROVED,
                    GraphStateKeys.ANSWER_STREAMED, false));
        } catch (Exception e) {
            log.error("[graph] 审批续跑写回裁决失败 session={} threadId={}", sessionId, threadId, e);
            return Flux.just(errorEvent("续跑失败：" + e.getMessage()));
        }
        log.info("[graph] 审批通过，从断点续跑 session={} threadId={} user={}", sessionId, threadId, userId);
        return renderStream(sshAgentGraph.resume(config), bridge, answerText, lastState, traceId, sessionId, false);
    }

    /**
     * 把图的节点输出、token 流、工具事件合并成一条 SSE 字符串流，并在最后补 done 事件。
     * <p>
     * 首次执行与审批续跑共用这段渲染逻辑：两条路的差别只在"图从哪里开始跑"，
     * 事件契约必须完全一致，否则前端要按入口写两套解析。
     */
    private Flux<String> renderStream(Flux<NodeOutput> graphOutput, TokenStreamBridge bridge,
                                      StringBuilder answerText, AtomicReference<OverAllState> lastState,
                                      String traceId, String sessionId, boolean summarySkipped) {
        Flux<String> nodeEvents = graphOutput
                .map(output -> {
                    // 顺手记下最后一个节点的 state：done 事件要报收口原因，
                    // 而收口原因只在 state 里，事件流本身不带。
                    lastState.set(output.state());
                    return nodeEvent(output);
                })
                // 图正常结束也要关管子：走 [reject] 分支时不会经过 AnswerNode，
                // 那里没人调 complete()，tokenEvents 不结束同样会让 merge 挂死。
                .doOnComplete(bridge::complete)
                .onErrorResume(error -> {
                    log.error("[graph] 图执行失败 session={}", sessionId, error);
                    // 关键：出错时必须把 token 管子关掉。
                    // Flux.merge(nodeEvents, tokenEvents) 要两个源都完成才会走 concatWith(done)，
                    // 只发一条 error 事件而不 complete，SSE 会一直挂到 Controller 的 10 分钟超时。
                    bridge.complete();
                    return Flux.just(errorEvent(String.valueOf(error.getMessage())));
                });

        Flux<String> tokenEvents = bridge.tokens()
                .map(token -> {
                    answerText.append(token);
                    return textEvent(token, answerText.toString());
                })
                .onErrorResume(error -> Flux.empty());

        // 工具事件（tool_call / tool_result）：由 [tool] / [observe] 节点自己发到
        // TokenStreamBridge.events()，这里原样并进 SSE——前端靠它渲染"执行中的工具卡片"。
        Flux<String> toolEvents = bridge.events()
                .map(JSON::toJSONString)
                .onErrorResume(error -> Flux.empty());

        // done 必须等三个源都完成后再发，且带上累积全文（老契约 {"event":"done","content":全文}）
        return Flux.merge(nodeEvents, tokenEvents, toolEvents)
                .concatWith(Flux.defer(() -> Flux.just(
                        doneEvent(answerText.toString(), bridge, lastState.get(), traceId, summarySkipped))));
    }

    private String nodeEvent(NodeOutput output) {
        Map<String, Object> event = new HashMap<>();
        event.put("event", GraphStateKeys.EVENT_NODE);
        event.put("type", GraphStateKeys.EVENT_NODE);
        event.put("node", output.node());
        return JSON.toJSONString(event);
    }

    /**
     * text 事件。
     * <p>
     * 同时下发 {@code event}（老契约，前端 {@code docs/dev-ops/nginx/html/index.html} 按它分支）
     * 与 {@code type}（新契约），并补 {@code fullText}（老契约有，前端用于"打字机"回填）。
     * 双写只多几十字节，换来前端零改动。
     */
    private String textEvent(String content, String fullText) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", GraphStateKeys.EVENT_TEXT);
        event.put("type", GraphStateKeys.EVENT_TEXT);
        event.put("content", content);
        event.put("fullText", fullText == null ? "" : fullText);
        return JSON.toJSONString(event);
    }

    /**
     * done 事件：老契约带累积全文（前端拿它兜底渲染最终答案），新契约也要有；
     * 顺带带上本次用量、收口原因与轮数，前端/日志据此判断"答完了"还是"被预算截断"。
     */
    private String doneEvent(String fullText, TokenStreamBridge bridge, OverAllState lastState,
                             String traceId, boolean summarySkipped) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", GraphStateKeys.EVENT_DONE);
        event.put("type", GraphStateKeys.EVENT_DONE);
        event.put("content", fullText == null ? "" : fullText);
        // 用户报障时只要贴这一个 id，就能把整轮请求（含子智能体）的日志捞出来。
        event.put("traceId", traceId == null ? "" : traceId);
        event.put("usage", bridge == null ? Map.of() : bridge.usage());
        String stopReason = "completed";
        if (lastState != null) {
            String reason = lastState.value(GraphStateKeys.STOP_REASON, "");
            if (reason != null && !reason.isBlank()) {
                stopReason = reason;
            }
            event.put("rounds", lastState.value(GraphStateKeys.ROUND, 0));
            event.put("toolCalls", lastState.value(GraphStateKeys.TOOL_CALL_COUNT, 0));
            // 被审批拦下时把单号一起下发：前端拿它调审批接口，后端据此找到那条检查点
            String approvalId = lastState.value(GraphStateKeys.APPROVAL_ID, "");
            if (approvalId != null && !approvalId.isBlank()) {
                event.put("approvalId", approvalId);
            }
        }
        event.put("stopReason", stopReason);
        // 该有摘要却没拿到（没配模型或调用失败）。前端/排查脚本据此判断"历史是不是丢了却没补"，
        // 不必再去翻日志找那条 warn。
        if (summarySkipped) {
            event.put("summarySkipped", true);
        }
        return JSON.toJSONString(event);
    }

    private String errorEvent(String message) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", GraphStateKeys.EVENT_ERROR);
        event.put("type", GraphStateKeys.EVENT_ERROR);
        event.put("content", message);
        event.put("message", message);
        return JSON.toJSONString(event);
    }
}