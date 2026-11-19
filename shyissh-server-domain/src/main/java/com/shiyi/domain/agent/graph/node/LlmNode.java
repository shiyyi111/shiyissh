package com.shiyi.domain.agent.graph.node;

import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.shiyi.domain.agent.graph.GraphStateKeys;
import com.shiyi.domain.agent.graph.support.BatchDispatchRequest;
import com.shiyi.domain.agent.graph.support.DispatchRequest;
import com.shiyi.domain.agent.graph.support.GraphAgentRegistry;
import com.shiyi.domain.agent.graph.support.GraphDispatchService;
import com.shiyi.domain.agent.graph.support.GraphMemoryToolService;
import com.shiyi.domain.agent.graph.support.GraphSshToolService;
import com.shiyi.domain.agent.graph.support.GraphToolRegistry;
import com.shiyi.domain.agent.graph.support.RecallMemoryRequest;
import com.shiyi.domain.agent.graph.support.SaveMemoryRequest;
import com.shiyi.domain.agent.graph.support.SaveWikiRequest;
import com.shiyi.domain.agent.graph.support.WikiSearchRequest;
import com.shiyi.domain.agent.graph.support.TokenStreamBridge;
import com.shiyi.domain.agent.model.valobj.AiAgentConfigTableVO;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [llm] 模型调用节点 —— 整个图的心脏。
 * <p>
 * 它做三件 插件回调做不到的事：
 * 1) <b>按轮次裁剪工具集</b>：最后一轮 {@code toolCallbacks(List.of())}，不给工具就必然收口
 *    （S3 spike 实测：不裁剪时模型会连续 4 轮换着命令查，永远不收口，最后被 recursionLimit 静默截断）；
 * 2) <b>拿到真实 toolCall 参数</b>：因为关了 {@code internalToolExecutionEnabled}，Spring AI 不会替我们执行，
 *    返回的 {@code AssistantMessage.getToolCalls()} 就是模型原始输出，command 参数是真的；
 * 3) <b>最后一轮流式输出</b>：`chatModel.stream()` 逐字 emit 到 token 桥，用户看到打字机效果。
 *
 * @author shiyi
 */
@Component
public class LlmNode implements GraphNode {

    private static final Logger log = LoggerFactory.getLogger(LlmNode.class);

    /** 默认轮数上限：3 轮足够"查一次 → 答一次"，再多就是在绕圈。 */
    public static final int DEFAULT_MAX_ROUNDS = 3;

    /** 默认工具调用次数预算（跨轮累计）。 */
    public static final int DEFAULT_MAX_TOOL_CALLS = 20;

    /** 默认 token 预算（prompt + completion 累计）。 */
    public static final int DEFAULT_MAX_TOTAL_TOKENS = 24000;

    /** 预算预警阈值：用到七成时提醒一次，让模型有机会自己提速收口。 */
    private static final double BUDGET_WARN_RATIO = 0.7d;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final GraphSshToolService sshTool;

    /** MCP / skills 补充工具；单测里手工 new 时为 null，此时只用内置 executeCommand。 */
    @Resource
    private GraphToolRegistry toolRegistry;

    /** 子智能体派发；单测里为 null 时不挂 dispatchSubAgent。 */
    @Resource
    private GraphDispatchService dispatchService;

    /** yml agents[] 定义（subAgents 决定挂不挂派发工具）。 */
    @Resource
    private GraphAgentRegistry agentRegistry;

    /** 图内置工具（记忆 / 知识库）的挂载与执行实现；单测里为 null 时不挂这组工具。 */
    @Resource
    private GraphMemoryToolService memoryTools;

    public LlmNode(ObjectProvider<ChatModel> chatModelProvider, GraphSshToolService sshTool) {
        this.chatModelProvider = chatModelProvider;
        this.sshTool = sshTool;
    }

    @Override
    public String name() {
        return GraphStateKeys.NODE_LLM;
    }

    @Override
    public AsyncNodeActionWithConfig action() {
        return AsyncNodeActionWithConfig.node_async((state, config) -> {
            List<Message> messages = new ArrayList<>(
                    state.<List<Message>>value(GraphStateKeys.MESSAGES).orElse(List.of()));
            int round = state.<Integer>value(GraphStateKeys.ROUND).orElse(0);
            int maxRounds = state.<Integer>value(GraphStateKeys.MAX_ROUNDS).orElse(DEFAULT_MAX_ROUNDS);
            TokenStreamBridge bridge = config.metadata("tokenSink").map(TokenStreamBridge.class::cast).orElse(null);
            String terminalSessionId = state.value(GraphStateKeys.TERMINAL_SESSION_ID, "");
            String sessionId = state.value(GraphStateKeys.SESSION_ID, "");
            String userId = state.value(GraphStateKeys.USER_ID, "default");
            String agentName = state.value(GraphStateKeys.AGENT_NAME, "");
            int depth = state.value(GraphStateKeys.DEPTH, 0);
            // 主机标识：记忆的 HOST 作用域按它分组，空则写入/召回 USER 级记忆。
            String hostKey = state.value(GraphStateKeys.HOST_KEY, "");
            // 意图：决定这一轮能看到哪些工具（闲聊不挂命令工具，纯解释只挂记忆工具）。
            String intent = state.value(GraphStateKeys.INTENT, "");

            int nextRound = round + 1;
            boolean roundLimit = nextRound >= maxRounds;

            // 三道闸门：轮数、工具次数、token 总量。任一用尽都进入"收口轮"——摘掉工具+提示直接作答。
            // 只靠轮数防不住"一轮里并发调八个工具"和"上下文越滚越大"，所以后两道是独立预算。
            int toolCallCount = state.value(GraphStateKeys.TOOL_CALL_COUNT, 0);
            int maxToolCalls = state.value(GraphStateKeys.MAX_TOOL_CALLS, DEFAULT_MAX_TOOL_CALLS);
            int promptTokens = state.value(GraphStateKeys.PROMPT_TOKENS, 0);
            int completionTokens = state.value(GraphStateKeys.COMPLETION_TOKENS, 0);
            int totalTokens = state.value(GraphStateKeys.TOTAL_TOKENS, 0);
            int maxTotalTokens = state.value(GraphStateKeys.MAX_TOTAL_TOKENS, DEFAULT_MAX_TOTAL_TOKENS);
            boolean toolBudgetExhausted = toolCallCount >= maxToolCalls;
            boolean tokenBudgetExhausted = totalTokens >= maxTotalTokens;
            boolean lastRound = roundLimit || toolBudgetExhausted || tokenBudgetExhausted;

            // 提前预警：等预算真的用尽再说"工具已禁用"，模型已经来不及换策略了。
            // 所以用到七成时先提醒一次，告诉它还剩多少额度、可以用几条命令收口。
            // 只提醒一次（BUDGET_WARNED 置位）是为了不破坏 prompt 前缀的稳定性。
            boolean budgetWarned = state.value(GraphStateKeys.BUDGET_WARNED, false);
            if (!lastRound && !budgetWarned
                    && (usedRatio(totalTokens, maxTotalTokens) >= BUDGET_WARN_RATIO
                    || usedRatio(toolCallCount, maxToolCalls) >= BUDGET_WARN_RATIO)) {
                messages.add(new UserMessage(budgetWarningHint(totalTokens, maxTotalTokens,
                        toolCallCount, maxToolCalls)));
                budgetWarned = true;
            }

            ChatModel chatModel = resolveChatModel();
            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                    .temperature(0.0)
                    .internalToolExecutionEnabled(false);

            if (!lastRound) {
                options.toolCallbacks(buildToolCallbacks(terminalSessionId, sessionId, userId, agentName, depth, hostKey, intent));
            } else {
                // 关键：收口轮把工具摘掉，模型只能基于已有信息作答
                options.toolCallbacks(List.of());
                if (!roundLimit) {
                    messages.add(new UserMessage(budgetExhaustedHint(toolBudgetExhausted, maxToolCalls,
                            tokenBudgetExhausted, maxTotalTokens)));
                }
                log.info("[llm] 第 {} 轮（收口轮），已裁剪工具集，强制收口；roundLimit={}, toolCalls={}/{}, tokens={}/{}",
                        nextRound, roundLimit, toolCallCount, maxToolCalls, totalTokens, maxTotalTokens);
            }

            Prompt prompt = new Prompt(messages, options.build());
            Map<String, Object> delta = new HashMap<>();
            delta.put(GraphStateKeys.ROUND, nextRound);
            delta.put(GraphStateKeys.BUDGET_WARNED, budgetWarned);

            if (lastRound) {
                // 末轮：流式逐字输出
                StringBuilder answer = new StringBuilder();
                List<ChatResponse> responses = chatModel.stream(prompt)
                        .doOnNext(response -> {
                            String text = text(response);
                            if (text != null && !text.isEmpty()) {
                                answer.append(text);
                                if (bridge != null) {
                                    bridge.emit(text);
                                }
                            }
                        })
                        .collectList()
                        .block();
                String finalAnswer = answer.toString();
                log.info("[llm] 第 {} 轮流式完成，回答长度={}, chunks={}", nextRound,
                        finalAnswer.length(), responses == null ? 0 : responses.size());
                delta.put(GraphStateKeys.ANSWER, finalAnswer);
                delta.put(GraphStateKeys.ANSWER_STREAMED, true);
                delta.put(GraphStateKeys.TOOL_CALLS, List.of());
                // 收口原因要带出去：前端与子智能体派发都靠它区分"正常答完"和"预算用尽被截断"。
                delta.put(GraphStateKeys.STOP_REASON,
                        stopReasonOf(roundLimit, toolBudgetExhausted, tokenBudgetExhausted));
                // 流式响应里 usage 只出现在最后一个非空块（OpenAI stream_options.include_usage），
                // 逐块累加会重复计数，这里取最后一块。
                delta.putAll(usageDelta(lastUsage(responses), promptTokens, completionTokens, totalTokens, bridge));
                return delta;
            }

            ChatResponse response = chatModel.call(prompt);
            AssistantMessage output = response == null ? null : response.getResult().getOutput();
            List<AssistantMessage.ToolCall> toolCalls =
                    output == null ? List.of() : output.getToolCalls();

            if (toolCalls != null && !toolCalls.isEmpty()) {
                for (AssistantMessage.ToolCall call : toolCalls) {
                    log.info("[llm] 第 {} 轮 toolCall: name={}, id={}, args={}",
                            nextRound, call.name(), call.id(), call.arguments());
                }
                delta.put(GraphStateKeys.TOOL_CALLS, toolCalls);
                delta.put(GraphStateKeys.MESSAGES, appendAssistant(messages, output));
            } else {
                // 模型直接给答案（比如 CHAT 意图）
                String answer = text(response);
                log.info("[llm] 第 {} 轮无工具调用，直接回答，长度={}", nextRound,
                        answer == null ? 0 : answer.length());
                if (bridge != null && answer != null && !answer.isBlank()) {
                    bridge.emit(answer);
                }
                delta.put(GraphStateKeys.ANSWER, answer == null ? "" : answer);
                delta.put(GraphStateKeys.ANSWER_STREAMED, bridge != null);
                delta.put(GraphStateKeys.TOOL_CALLS, List.of());
                delta.put(GraphStateKeys.STOP_REASON, "completed");
            }
            delta.putAll(usageDelta(usageOf(response), promptTokens, completionTokens, totalTokens, bridge));
            return delta;
        });
    }

    /**
     * 收口原因：三道闸里哪一道先用尽就记哪一道。
     * <p>
     * 顺序上轮数优先：轮数到顶时通常工具次数也快满了，但对用户来说"聊了几轮"比
     * "调了几次工具"更容易理解，报轮数更有解释力。
     */
    private String stopReasonOf(boolean roundLimit, boolean toolBudgetExhausted,
                                boolean tokenBudgetExhausted) {
        if (roundLimit) {
            return "max_rounds";
        }
        if (toolBudgetExhausted) {
            return "max_tool_calls";
        }
        if (tokenBudgetExhausted) {
            return "max_tokens";
        }
        return "completed";
    }

    /** 累加本轮用量并写回 state；顺带同步给 token 桥，让 done 事件带出总用量。 */
    private Map<String, Object> usageDelta(int[] usage, int promptTokens, int completionTokens,
                                           int totalTokens, TokenStreamBridge bridge) {
        int prompt = promptTokens;
        int completion = completionTokens;
        int total = totalTokens;
        if (usage != null) {
            prompt += usage[0];
            completion += usage[1];
            total += usage[2] > 0 ? usage[2] : usage[0] + usage[1];
        }
        Map<String, Object> delta = new HashMap<>();
        delta.put(GraphStateKeys.PROMPT_TOKENS, prompt);
        delta.put(GraphStateKeys.COMPLETION_TOKENS, completion);
        delta.put(GraphStateKeys.TOTAL_TOKENS, total);
        if (bridge != null) {
            bridge.recordUsage(prompt, completion, total);
        }
        return delta;
    }

    /** 取一条响应里的 usage；没有就返回 null（离线模型/网关不透传 usage 时很常见）。 */
    private int[] usageOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null
                || response.getMetadata().getUsage() == null) {
            return null;
        }
        org.springframework.ai.chat.metadata.Usage usage = response.getMetadata().getUsage();
        return new int[]{nonNegative(usage.getPromptTokens()),
                nonNegative(usage.getCompletionTokens()),
                nonNegative(usage.getTotalTokens())};
    }

    /** 流式响应取最后一个非空 usage。 */
    private int[] lastUsage(List<ChatResponse> responses) {
        if (responses == null) {
            return null;
        }
        for (int i = responses.size() - 1; i >= 0; i--) {
            int[] usage = usageOf(responses.get(i));
            if (usage != null) {
                return usage;
            }
        }
        return null;
    }

    private static int nonNegative(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    /** 预算用尽时追加给模型的收口提示（不是 SystemMessage：中途插入的系统消息容易被模型忽略）。 */
    private String budgetExhaustedHint(boolean toolBudgetExhausted, int maxToolCalls,
                                       boolean tokenBudgetExhausted, int maxTotalTokens) {
        StringBuilder hint = new StringBuilder("（系统提示：本次请求的");
        if (toolBudgetExhausted) {
            hint.append("工具调用次数已达上限 ").append(maxToolCalls).append(" 次");
        }
        if (tokenBudgetExhausted) {
            if (toolBudgetExhausted) {
                hint.append("且");
            }
            hint.append("token 用量已达预算 ").append(maxTotalTokens);
        }
        hint.append("，工具已被禁用。请基于已有信息直接给出最终结论；")
                .append("无法确认的部分明确说明「缺少哪些信息」，不要再请求执行命令。）");
        return hint.toString();
    }

    /**
     * 预算预警提示：用量过半时告诉模型还剩多少，让它自己决定收口节奏。
     * <p>
     * 与 {@link #budgetExhaustedHint} 的区别是"还来得及"与"已经晚了"——后者工具已摘，
     * 只能基于已有信息答；前者还能再调几次，但要有意识地少调。
     */
    private String budgetWarningHint(int totalTokens, int maxTotalTokens,
                                     int toolCallCount, int maxToolCalls) {
        int restTokens = Math.max(0, maxTotalTokens - totalTokens);
        int restCalls = Math.max(0, maxToolCalls - toolCallCount);
        return "（系统提示：本次请求的 token 已用掉 "
                + percent(totalTokens, maxTotalTokens) + "%，剩余约 " + restTokens + "；"
                + "工具调用 " + toolCallCount + "/" + maxToolCalls + " 次，剩余 " + restCalls + " 次。"
                + "请优先用最少、最关键的 1~2 条命令补齐信息，然后直接给出结论；"
                + "能基于现有信息回答的部分就先回答，不要把命令留到最后。）";
    }

    /** 百分比，分母为零时按 0 处理。 */
    private static int percent(int used, int total) {
        return total <= 0 ? 0 : (int) Math.min(100, Math.round(used * 100d / total));
    }

    /** 使用比例，分母为零时按 1（视为已用尽）。 */
    private static double usedRatio(int used, int total) {
        return total <= 0 ? 1d : Math.min(1d, used * 1d / total);
    }

    private List<Message> appendAssistant(List<Message> messages, AssistantMessage output) {
        List<Message> next = new ArrayList<>(messages);
        next.add(output);
        return next;
    }

    private String text(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    /**
     * 工具可见性策略：按意图把"这一轮不该出现的工具"裁掉。
     * <p>
     * 为什么要在工具数量上做减法，而不是把所有工具都塞给模型：
     * <ul>
     *   <li>工具清单会随 MCP/skills 一起进系统提示，个数越多、选错的概率越高；</li>
     *   <li>"今天天气怎么样"这种闲聊，如果能看见 {@code executeCommand}，模型偶尔真的会去跑一条命令
     *       —— 多算一次 token、多开一次审批，用户还莫名其妙；</li>
     *   <li>纯解释类问题（"502 是什么原因"）不需要执行任何东西，只留记忆工具既能自查历史、又不会误操作。</li>
     * </ul>
     * 判定原则是"只在能确定的时候收窄"：意图为空、UNKNOWN、COMPOUND、CONTINUE 以及所有业务意图都走
     * {@link #FULL}，因为意图识别本身可能出错，不能让它成为"模型看不见工具"的唯一原因。
     */
    public enum ToolPolicy {
        /** 闲聊：不给任何工具。 */
        NONE(false, false, false),
        /** 纯解释：只给记忆工具（查历史结论、记下新结论），不给命令与派发。 */
        MEMORY_ONLY(false, false, true),
        /** 其余全部：命令、MCP/skills、派发、记忆。 */
        FULL(true, true, true);

        /** 是否挂 executeCommand 与 MCP/skills 工具。 */
        private final boolean shell;
        /** 是否挂 dispatchSubAgent / dispatchSubAgents。 */
        private final boolean dispatch;
        /** 是否挂 recallMemory / saveMemory。 */
        private final boolean memory;

        ToolPolicy(boolean shell, boolean dispatch, boolean memory) {
            this.shell = shell;
            this.dispatch = dispatch;
            this.memory = memory;
        }

        public boolean allowsShell() {
            return shell;
        }

        public boolean allowsDispatch() {
            return dispatch;
        }

        public boolean allowsMemory() {
            return memory;
        }
    }

    /** 意图 → 工具可见性。大小写与前后空白都容错，因为 INTENT 可能来自规则层、LLM 或 state 默认值。 */
    public static ToolPolicy toolPolicyOf(String intent) {
        if (intent == null) {
            return ToolPolicy.FULL;
        }
        String normalized = intent.strip().toUpperCase(java.util.Locale.ROOT);
        if ("CHAT".equals(normalized)) {
            return ToolPolicy.NONE;
        }
        if ("EXPLAIN".equals(normalized)) {
            return ToolPolicy.MEMORY_ONLY;
        }
        return ToolPolicy.FULL;
    }

    private ToolCallback[] buildToolCallbacks(String terminalSessionId, String sessionId, String userId,
                                              String agentName, int depth, String hostKey, String intent) {
        ToolPolicy policy = toolPolicyOf(intent);
        List<ToolCallback> callbacks = new ArrayList<>();
        if (policy.allowsShell()) {
            callbacks.add(FunctionToolCallback
                    .builder("executeCommand", (CommandRequest request) -> sshTool.execute(request.command(), terminalSessionId))
                    .description("在远端 Linux 主机执行 shell 命令并返回标准输出。只读排查命令优先，例如 df -h、free -m、ps aux。")
                    .inputType(CommandRequest.class)
                    .build());
        }
        // MCP 服务与本地 skills：来自 yml 里 chat-model 的 tool-mcp-list / tool-skills-list，
        // 按 agent 所属表过滤：每个 Agent 只拿自己那张表声明的工具，避免工具串台；
        // 早期版本并成全局数组，导致 SSH Agent 手里出现 baidu-search（模型会拿它当 shell 试）。
        if (policy.allowsShell() && toolRegistry != null) {
            ToolCallback[] extra = toolRegistry.extraTools(agentName);
            if (extra != null && extra.length > 0) {
                callbacks.addAll(Arrays.asList(extra));
            }
        }
        // 子智能体派发：给声明了 subAgents 的 Agent 挂 dispatchSubAgent 工具。
        if (policy.allowsDispatch() && dispatchService != null && agentRegistry != null
                && depth < GraphDispatchService.MAX_DEPTH) {
            List<AiAgentConfigTableVO.Module.Agent> subAgents = agentRegistry.subAgentsOf(agentName);
            if (!subAgents.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (AiAgentConfigTableVO.Module.Agent sub : subAgents) {
                    names.add(sub.getName() + "（" + sub.getDescription() + "）");
                }
                callbacks.add(FunctionToolCallback
                        .builder("dispatchSubAgent",
                                (DispatchRequest request) -> dispatchService.dispatch(
                                        request.agent(), request.task(), sessionId, userId, terminalSessionId, depth))
                        .description("把一个子任务派发给子智能体，返回它的最终结论。可用的子智能体：" + String.join("；", names))
                        .inputType(DispatchRequest.class)
                        .build());
                // 批量并行派发：这里只声明工具，真正执行在 [tool] 节点（GraphOrchestrator）。
                // 这里的 lambda 体不会被调用（internalToolExecutionEnabled=false），只负责生成 JSON Schema 与 description，
                // 所以参数类型用 BatchDispatchRequest、返回值给一句提示即可。
                callbacks.add(FunctionToolCallback
                        .builder("dispatchSubAgents",
                                (BatchDispatchRequest request) -> "（批量派发由 [tool] 节点执行）")
                        .description("同时把多个子任务派发给子智能体并行执行，返回每个任务的执行结果。"
                                + "任务之间可以用 dependsOn 声明先后依赖，无依赖的任务会被并行执行。"
                                + "适合一次排查多方面问题（如同时看磁盘、内存、进程）或先诊断再变更。"
                                + "涉及写操作时把 maxConcurrency 设为 1，避免多个子智能体同时改动同一台机器。"
                                + "可用的子智能体：" + String.join("；", names))
                        .inputType(BatchDispatchRequest.class)
                        .build());
            }
        }
        // 记忆工具化：把"查记忆 / 记结论"变成模型可主动调用的动作。
        // 自动注入解决"每轮都该看到的那几条"，这两个工具解决"模型临时想查、想记"的场景：
        // 注入的查询词是入口固定的，模型中途想确认某个细节时没法改变注入内容，只能自己调工具。
        if (policy.allowsMemory() && memoryTools != null) {
            callbacks.add(FunctionToolCallback
                    .builder("recallMemory",
                            (RecallMemoryRequest request) -> memoryTools.recallMemory(userId, hostKey, request))
                    .description("按需检索长期记忆：以前的排查结论、这台机器的环境事实、用户偏好。"
                            + "当需要确认历史信息但当前上下文里没有时调用；query 用自然语言描述想找什么。")
                    .inputType(RecallMemoryRequest.class)
                    .build());
            callbacks.add(FunctionToolCallback
                    .builder("saveMemory",
                            (SaveMemoryRequest request) -> memoryTools.saveMemory(userId, sessionId, hostKey, request))
                    .description("把一条值得长期记住的结论写进记忆，例如这台机器的挂载布局、软件安装路径、"
                            + "验证有效的处理办法。只记脱离上下文也能看懂的结论，不要记录命令输出原文。")
                    .inputType(SaveMemoryRequest.class)
                    .build());
        }
        // 知识库：与记忆工具同组——闲聊不需要，解释类问题最需要（先查已有经验再回答）。
        // 与 recallMemory 的分工：记忆是"这次会话/这个用户遇到过什么"，wiki 是"团队沉淀的通用经验"。
        if (policy.allowsMemory() && memoryTools != null) {
            callbacks.add(FunctionToolCallback
                    .builder("wikiSearch",
                            (WikiSearchRequest request) -> memoryTools.wikiSearch(userId, hostKey, request))
                    .description("检索团队沉淀的运维知识库（历史故障的根因与处理办法）。"
                            + "排查开始前、或需要确认某个已知问题的标准处理方式时优先调用；"
                            + "返回值带编号，回答里引用时写 [编号]。")
                    .inputType(WikiSearchRequest.class)
                    .build());
            callbacks.add(FunctionToolCallback
                    .builder("saveWiki",
                            (SaveWikiRequest request) -> memoryTools.saveWiki(userId, hostKey, request))
                    .description("把一次排查的结论沉淀进知识库，供以后复用。"
                            + "只在真正定位到根因后调用；标题写「现象 → 原因」，正文写根因、处理动作与验证方式。")
                    .inputType(SaveWikiRequest.class)
                    .build());
        }
        return callbacks.toArray(new ToolCallback[0]);
    }

    /**
     * 取模型：只认容器里的 {@code ChatModel} bean（由 {@code GraphChatModelConfig} 按 yml 装配）。
     * <p>
     * 以前这里有一个"按系统属性/环境变量现造一个模型"的兜底工厂，和配置类各读一遍配置，
     * 结果出问题时两处配置谁生效说不清楚。现在只有一条装配路径，
     * 缺 bean 直接给出可执行的排查提示，而不是悄悄换一套配置继续跑。
     */
    private ChatModel resolveChatModel() {
        ChatModel available = chatModelProvider.getIfAvailable();
        if (available != null) {
            return available;
        }
        throw new IllegalStateException("没有可用的 ChatModel bean：请检查 ai.agent.config 里 module.ai-api.api-key "
                + "是否正确（或设置 llm.api-key / DASHSCOPE_API_KEY 环境变量），"
                + "装配逻辑见 GraphChatModelConfig。");
    }

    /** 工具入参：字段名就是模型看到的参数名。 */
    public record CommandRequest(
            @com.fasterxml.jackson.annotation.JsonPropertyDescription("要执行的 shell 命令，例如 df -h")
            String command) {
    }
}
