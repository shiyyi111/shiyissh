package com.shiyi.domain.agent.graph;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;

/**
 * 图状态键与节点名常量。
 * <p>
 * 图里唯一允许的通信通道是 state：节点之间不互相注入、不共享字段，
 * 所有跨节点数据都从这里走。集中定义是为了避免拼错 key 导致"值悄悄丢失"。
 *
 * @author shiyi
 */
public final class GraphStateKeys {

    /** 消息历史（SystemMessage / UserMessage / AssistantMessage / ToolResponseMessage）。 */
    public static final String MESSAGES = "messages";

    /**
     * 被裁剪掉的历史压成的摘要文本（由入口计算、[enrich] 拼进消息列表）。
     * <p>
     * 为什么单独放一个 state 键、而不是直接往 {@link #MESSAGES} 头部塞一条 SystemMessage：
     * 那个位置只能有一个主人（system instruction），两个都往里写就会互相覆盖，
     * 表现为"聊到第 N 轮人格丢了"。这里只承载内容，拼装位置由 [enrich] 统一决定。
     */
    public static final String HISTORY_SUMMARY = "historySummary";

    /** 本轮模型返回的工具调用列表。 */
    public static final String TOOL_CALLS = "toolCalls";

    /** guard 节点解析出来的真实命令。 */
    public static final String COMMAND = "command";

    /** 工具执行结果（字符串，兼容单工具场景）。 */
    public static final String TOOL_OUTPUT = "toolOutput";

    /** 工具执行结果（toolCallId -> 输出），一次模型调用可能并发多个工具。 */
    public static final String TOOL_OUTPUTS = "toolOutputs";

    /** guard 的判定：PASS / BLOCK。整批结论，供条件边分流。 */
    public static final String GUARD_VERDICT = "guardVerdict";

    /** guard 的逐条判定（toolCallId -> PASS/BLOCK/CONFIRM），供 [tool] 单条短路。 */
    public static final String GUARD_VERDICTS = "guardVerdicts";

    /** guard 的逐条原因（toolCallId -> 原因），被拒时回灌给模型。 */
    public static final String GUARD_REASONS = "guardReasons";

    /** guard 对整批的判定原因，[reject] 直接展示给用户（逐条原因在 GUARD_REASONS 里）。 */
    public static final String GUARD_REASON = "guardReason";

    /**
     * 本轮登记的人工审批单号（CONFIRM 时由 [guard] 写入）。
     * <p>
     * 只在需要确认时出现；前端拿它去调审批接口。
     */
    public static final String APPROVAL_ID = "approvalId";

    /**
     * 待确认命令的提示文本（[guard] 生成，[approval] 节点直接用作回答）。
     * <p>
     * 让 [guard] 生成而不是 [approval] 拼，是因为"命令 + 原因 + 单号"只有 guard 都有。
     */
    public static final String APPROVAL_PROMPT = "approvalPrompt";

    /**
     * 用户已批准、本轮可直接放行的命令。
     * <p>
     * 由审批接口在用户点确认后，随新一次请求写进 state；[guard] 看到它就把该条命令
     * 判为 PASS。为什么按"命令原文相等"匹配而不是按单号：单号只证明"某个请求被批准过"，
     * 命令原文才能证明"批准的正是这一条"，否则模型换个命令就能搭便车。
     */
    public static final String APPROVED_COMMAND = "approvedCommand";

    /**
     * 人工审批的裁决结果（断点重续时注入），取值 {@link #APPROVAL_APPROVED} / {@link #APPROVAL_REJECTED}。
     * <p>
     * 它不是"给模型看的上下文"，而是"驱动流程的控制信号"：图在 [approval] 之前中断，
     * 用户点确认后我们把裁决写进这个键再恢复执行，[approval] 据此决定
     * 是继续执行已批准的命令（转 [tool]），还是直接收口说明已取消。
     */
    public static final String APPROVAL_DECISION = "approvalDecision";

    /** 审批通过：命令放行执行。 */
    public static final String APPROVAL_APPROVED = "approved";

    /** 审批驳回：命令不执行，直接给用户一个明确的收尾说明。 */
    public static final String APPROVAL_REJECTED = "rejected";

    /** 每个工具调用是否成功（toolCallId -> Boolean），[observe] 判定后写入。 */
    public static final String TOOL_SUCCESS = "toolSuccess";

    /** 最终回答。 */
    public static final String ANSWER = "answer";

    /** 当前第几轮模型调用。 */
    public static final String ROUND = "round";

    /** 轮次预算。 */
    public static final String MAX_ROUNDS = "maxRounds";

    /** 已执行的工具调用次数（跨轮累计），用于工具次数预算。 */
    public static final String TOOL_CALL_COUNT = "toolCallCount";

    /** 工具调用次数预算；用尽后不再执行任何工具，强制模型收口。 */
    public static final String MAX_TOOL_CALLS = "maxToolCalls";

    /** 累计 prompt token（模型返回的 usage 之和）。 */
    public static final String PROMPT_TOKENS = "promptTokens";

    /** 累计 completion token。 */
    public static final String COMPLETION_TOKENS = "completionTokens";

    /** 累计 total token。 */
    public static final String TOTAL_TOKENS = "totalTokens";

    /** token 预算；用尽后摘掉工具、追加收口提示，避免无上限烧钱。 */
    public static final String MAX_TOTAL_TOKENS = "maxTotalTokens";

    /**
     * 预算预警标记：用量过半时给模型发过一次"该收口了"的提示后置 true。
     * <p>
     * 只发一次是有意的：每轮都往 messages 里塞提示会让 prompt 前缀不断变化，
     * 前缀一变缓存就失效，反而更费钱。
     */
    public static final String BUDGET_WARNED = "budgetWarned";

    /** 意图标签。 */
    public static final String INTENT = "intent";

    /**
     * 意图识别结果（{@code IntentResultVO} 的 JSON 字符串）。
     * <p>
     * 存 JSON 而不是对象：SAA 每个节点后会把整个 state 序列化（Jackson），
     * 存对象会依赖反序列化后的类型；存字符串则任何序列化器都不会丢东西。
     * 反馈回路（{@code [observe]}）从这里还原出上一次的意图结果做重分类。
     */
    public static final String INTENT_RESULT = "intentResult";

    /** 会话 id。 */
    public static final String SESSION_ID = "sessionId";

    /** 用户 id。 */
    public static final String USER_ID = "userId";

    /** 终端会话 id（可选）。 */
    public static final String TERMINAL_SESSION_ID = "terminalSessionId";

    /**
     * 主机标识（SSH 连接 ID），请求入口解析一次后写入 state。
     * <p>
     * 长期记忆的 HOST 作用域按这个值分组：环境事实、软件版本、该机器的排查结论
     * 只对对应主机有效。为空表示"没有确定主机"，记忆退化为 USER 级。
     */
    public static final String HOST_KEY = "hostKey";

    /** 当前执行的 Agent 名称（yml agents[].name）；为空时用默认 Agent。 */
    public static final String AGENT_NAME = "agentName";

    /**
     * 本次请求的追踪 ID（16 位十六进制）。
     * <p>
     * 一次排查跨 HTTP 线程、图节点线程、子智能体线程、并发批量派发线程，
     * 出问题时要能把散落在几千行日志里的记录串起来，只能靠这个 id。
     * 它同时写进 MDC（{@code trace-id}）供日志输出，并随 done 事件回给前端，
     * 用户报障时贴一个 id 就能定位整轮请求。
     */
    public static final String TRACE_ID = "traceId";

    /** 派发深度：0 = 用户直连的父 Agent，>0 = 被派发的子 Agent（用于禁止无限递归派发）。 */
    public static final String DEPTH = "depth";

    /**
     * 最终回答是否已经流式发给前端。
     * <p>
     * [llm] 末轮是边生成边推 token 的，[answer] 只负责补一个 complete；
     * 而 [reject] / [approval] 生成的文本从未推送过，[answer] 必须整段补发。
     * 用一个显式布尔位区分这两条路径，避免"要么重复发一遍、要么一个字都不发"。
     */
    public static final String ANSWER_STREAMED = "answerStreamed";

    /**
     * 本次请求的收口原因，取值与 {@code ReActResultDTO.stopReason} 一致：
     * completed / max_rounds / max_tool_calls / max_tokens / intent_reject / error。
     * <p>
     * 为什么要一路带到 done 事件：前端只看到一段回答，分不清"模型正常答完"和
     * "预算用尽被强制收口"。前者可以继续追问，后者应当提示用户换个问法或缩小范围。
     * 子智能体派发时这个值也会被装进回传信封，父模型据此决定重试还是换策略。
     */
    public static final String STOP_REASON = "stopReason";

    /**
     * SSE 事件类型。取值必须与 {@code GraphChatService} 实际发出的事件一一对应，
     * 前端按这里的字符串分发处理：node = 节点进度、text = token 增量、
     * done = 收口（带全量回答与 usage）、error = 失败、tool_call / tool_result = 工具进度、
     * meta = 首帧元信息（只出现在审批端点，带 traceId 与单号）。
     */
    public static final String EVENT_NODE = "node";

    /** 事件类型：token 增量（endpoint 用 text 而不是 token，因为一次推送可能是多字）。 */
    public static final String EVENT_TEXT = "text";

    /** 事件类型：本次请求收口，content 是完整回答。 */
    public static final String EVENT_DONE = "done";

    /** 事件类型：错误。 */
    public static final String EVENT_ERROR = "error";

    /** 事件类型：工具调用开始（前端画"正在执行 xxx"）。 */
    public static final String EVENT_TOOL_CALL = "tool_call";

    /** 事件类型：工具执行结果。 */
    public static final String EVENT_TOOL_RESULT = "tool_result";

    /** 事件类型：首帧元信息。 */
    public static final String EVENT_META = "meta";

    /**
     * 事件类型：子智能体派发开始。
     * <p>
     * 派发是同步阻塞的：父图在等子图跑完这段时间里没有任何 token 输出，
     * 前端如果只看 token 流，会以为请求卡死。补一对 dispatch_start / dispatch_end
     * 的进度事件，前端据此显示"正在派发给 sshDiagnosisAgent"。
     */
    public static final String EVENT_DISPATCH_START = "dispatch_start";

    /** 事件类型：子智能体派发结束（带 status / 耗时 / 尝试次数 / 用量）。 */
    public static final String EVENT_DISPATCH_END = "dispatch_end";

    /** 节点名。 */
    public static final String NODE_INTENT = "intent";
    public static final String NODE_ENRICH = "enrich";
    public static final String NODE_LLM = "llm";
    public static final String NODE_GUARD = "guard";
    public static final String NODE_TOOL = "tool";
    public static final String NODE_OBSERVE = "observe";
    public static final String NODE_REJECT = "reject";

    /** 人工审批提示节点：CONFIRM 时不执行命令，只把"待确认"结论作为回答返回。 */
    public static final String NODE_APPROVAL = "approval";
    public static final String NODE_ANSWER = "answer";

    private GraphStateKeys() {
    }

    /**
     * 所有键用 REPLACE 策略：节点返回的是"该键的新值"，不是增量。
     * messages 这种列表也是整体替换（节点里自己 new ArrayList 追加），
     * 避免 APPEND 策略在并发下产生不可预期的顺序问题。
     */
    public static KeyStrategyFactory keyStrategies() {
        return KeyStrategy.builder()
                .addStrategy(MESSAGES, KeyStrategy.REPLACE)
                .addStrategy(TOOL_CALLS, KeyStrategy.REPLACE)
                .addStrategy(COMMAND, KeyStrategy.REPLACE)
                .addStrategy(TOOL_OUTPUT, KeyStrategy.REPLACE)
                .addStrategy(TOOL_OUTPUTS, KeyStrategy.REPLACE)
                .addStrategy(GUARD_VERDICT, KeyStrategy.REPLACE)
                .addStrategy(GUARD_VERDICTS, KeyStrategy.REPLACE)
                .addStrategy(GUARD_REASONS, KeyStrategy.REPLACE)
                .addStrategy(GUARD_REASON, KeyStrategy.REPLACE)
                .addStrategy(APPROVAL_ID, KeyStrategy.REPLACE)
                .addStrategy(APPROVAL_PROMPT, KeyStrategy.REPLACE)
                .addStrategy(APPROVED_COMMAND, KeyStrategy.REPLACE)
                .addStrategy(APPROVAL_DECISION, KeyStrategy.REPLACE)
                .addStrategy(HISTORY_SUMMARY, KeyStrategy.REPLACE)
                .addStrategy(TOOL_SUCCESS, KeyStrategy.REPLACE)
                .addStrategy(ANSWER, KeyStrategy.REPLACE)
                .addStrategy(ROUND, KeyStrategy.REPLACE)
                .addStrategy(MAX_ROUNDS, KeyStrategy.REPLACE)
                .addStrategy(TOOL_CALL_COUNT, KeyStrategy.REPLACE)
                .addStrategy(MAX_TOOL_CALLS, KeyStrategy.REPLACE)
                .addStrategy(PROMPT_TOKENS, KeyStrategy.REPLACE)
                .addStrategy(COMPLETION_TOKENS, KeyStrategy.REPLACE)
                .addStrategy(TOTAL_TOKENS, KeyStrategy.REPLACE)
                .addStrategy(MAX_TOTAL_TOKENS, KeyStrategy.REPLACE)
                .addStrategy(BUDGET_WARNED, KeyStrategy.REPLACE)
                .addStrategy(INTENT, KeyStrategy.REPLACE)
                .addStrategy(INTENT_RESULT, KeyStrategy.REPLACE)
                .addStrategy(SESSION_ID, KeyStrategy.REPLACE)
                .addStrategy(USER_ID, KeyStrategy.REPLACE)
                .addStrategy(TERMINAL_SESSION_ID, KeyStrategy.REPLACE)
                .addStrategy(HOST_KEY, KeyStrategy.REPLACE)
                .addStrategy(AGENT_NAME, KeyStrategy.REPLACE)
                .addStrategy(TRACE_ID, KeyStrategy.REPLACE)
                .addStrategy(DEPTH, KeyStrategy.REPLACE)
                .addStrategy(ANSWER_STREAMED, KeyStrategy.REPLACE)
                .addStrategy(STOP_REASON, KeyStrategy.REPLACE)
                .defaultStrategy(KeyStrategy.REPLACE)
                .build();
    }
}
