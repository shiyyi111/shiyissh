package com.shiyi.domain.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.fastjson2.JSON;
import com.shiyi.domain.agent.graph.GraphStateKeys;
import com.shiyi.domain.agent.graph.support.BatchDispatchRequest;
import com.shiyi.domain.agent.graph.support.DispatchRequest;
import com.shiyi.domain.agent.graph.support.DispatchResult;
import com.shiyi.domain.agent.graph.support.GraphDispatchService;
import com.shiyi.domain.agent.graph.support.GraphMemoryToolService;
import com.shiyi.domain.agent.graph.support.GraphOrchestrator;
import com.shiyi.domain.agent.graph.support.GraphSshToolService;
import com.shiyi.domain.agent.graph.support.GraphToolRegistry;
import com.shiyi.domain.agent.graph.support.RecallMemoryRequest;
import com.shiyi.domain.agent.graph.support.SaveMemoryRequest;
import com.shiyi.domain.agent.graph.support.SaveWikiRequest;
import com.shiyi.domain.agent.graph.support.TokenStreamBridge;
import com.shiyi.domain.agent.graph.support.WikiSearchRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [tool] 工具执行节点。
 * <p>
 * 同一个执行入口、同一套"按终端会话执行"策略，
 * 只是调用方从"Spring AI 内部执行"变成"图里的一个节点"，因此参数是我们自己解析的真实值。
 * <p>
 * 一次模型调用可能并发多个工具调用（{@code df -h} + {@code df -i}），这里逐个执行，
 * 结果按 {@code toolCallId → 输出} 存进 {@link GraphStateKeys#TOOL_OUTPUTS}，
 * 由 [observe] 节点一次性回填成 {@code ToolResponseMessage}（多个 response 一条消息）。
 * <p>
 * 终端会话 ID 来自图 state（{@link GraphStateKeys#TERMINAL_SESSION_ID}），不再是 ThreadLocal ——
 * 这是换图之后顺带解决的老问题：并发请求互相覆盖、线程池复用拿不到会话。
 *
 * @author shiyi
 */
@Component
public class ToolNode implements GraphNode {

    private static final Logger log = LoggerFactory.getLogger(ToolNode.class);

    /** 工具名：SSH 命令执行。 */
    public static final String TOOL_EXECUTE_COMMAND = "executeCommand";
    /** 工具名：派发子智能体。 */
    public static final String TOOL_DISPATCH_SUB_AGENT = "dispatchSubAgent";
    /** 工具名：批量并行派发子智能体。 */
    public static final String TOOL_DISPATCH_SUB_AGENTS = "dispatchSubAgents";
    /** 工具名：按需检索长期记忆。 */
    public static final String TOOL_RECALL_MEMORY = "recallMemory";
    /** 工具名：写入长期记忆。 */
    public static final String TOOL_SAVE_MEMORY = "saveMemory";
    /** 工具名：检索运维知识库。 */
    public static final String TOOL_WIKI_SEARCH = "wikiSearch";
    /** 工具名：写入运维知识库。 */
    public static final String TOOL_SAVE_WIKI = "saveWiki";

    private final GraphSshToolService sshTool;

    /**
     * 子智能体派发服务。
     * <p>
     * 用字段注入而不是构造器注入：{@code SshAgentGraph} → {@code ToolNode} → {@code GraphDispatchService}
     * → {@code ObjectProvider<SshAgentGraph>} 虽然靠 ObjectProvider 延迟解析不会真的成环，
     * 但字段注入能让单测直接 {@code new ToolNode(sshTool)} 而不用构造整个派发服务。
     * 为 null 时（单测）派发工具退回"不支持"提示，不影响命令执行。
     */
    @Resource
    private GraphDispatchService dispatchService;

    /**
     * 批量并行编排器。
     * <p>
     * 同样是字段注入：单测里为 null 时批量派发退回"未装配"提示，不影响单任务派发与命令执行。
     */
    @Resource
    private GraphOrchestrator orchestrator;

    /**
     * 补充工具注册表（MCP / skills）。
     * <p>
     * 先按工具名分流；名字匹配不上时再看参数里有没有 command，
     * 取不到 {@code command} 就返回"(空命令，未执行)"——MCP 工具等于永远不可用。
     * 这里改为：名字对不上内置工具时，去注册表里找同名 {@link ToolCallback} 并调它的
     * {@code call(args)}（Spring AI 的工具契约），这才是"真正执行 MCP/skills 工具"。
     */
    @Resource
    private GraphToolRegistry toolRegistry;

    /**
     * 图内置工具（记忆 / 知识库）的执行实现。
     * <p>
     * 与 {@code LlmNode} 共用同一份实现：那里负责"挂给模型看"，这里负责"真的执行"。
     * 两处若各写一份，就会出现模型能看见工具、调用后却拿到未知工具的接不上线问题。
     */
    @Resource
    private GraphMemoryToolService memoryTools;

    public ToolNode(GraphSshToolService sshTool) {
        this.sshTool = sshTool;
    }

    @Override
    public String name() {
        return GraphStateKeys.NODE_TOOL;
    }

    @Override
    public AsyncNodeActionWithConfig action() {
        return AsyncNodeActionWithConfig.node_async((OverAllState state, RunnableConfig config) -> {
            List<AssistantMessage.ToolCall> toolCalls =
                    state.<List<AssistantMessage.ToolCall>>value(GraphStateKeys.TOOL_CALLS).orElse(List.of());
            String terminalSessionId = state.value(GraphStateKeys.TERMINAL_SESSION_ID, "");
            // 派发子智能体要用到的会话上下文（子图需要自己的 sessionId / depth）
            String sessionId = state.value(GraphStateKeys.SESSION_ID, "");
            String userId = state.value(GraphStateKeys.USER_ID, "default");
            String agentName = state.value(GraphStateKeys.AGENT_NAME, "");
            int depth = state.value(GraphStateKeys.DEPTH, 0);
            // 追踪 ID：透传给子智能体/批量派发，让它们的日志和父请求落在同一个 trace 里。
            String traceId = state.value(GraphStateKeys.TRACE_ID, "");
            // 记忆/知识库按主机维度隔离：环境事实只对这台机器成立，换机器就不该被召回
            String hostKey = state.value(GraphStateKeys.HOST_KEY, "");
            // 父级剩余 token 额度：派发子图时作为子图上限，把预算闸门前移，
            // 否则子图各自领一份 24000，父级只在事后记账，一次派发就能超支。
            int maxTotalTokens = state.value(GraphStateKeys.MAX_TOTAL_TOKENS, LlmNode.DEFAULT_MAX_TOTAL_TOKENS);
            int usedTotalTokens = state.value(GraphStateKeys.TOTAL_TOKENS, 0);
            int remainingTokens = Math.max(0, maxTotalTokens - usedTotalTokens);

            Map<String, String> verdicts = state.<Map<String, String>>value(GraphStateKeys.GUARD_VERDICTS)
                    .orElse(Map.of());
            Map<String, String> guardReasons = state.<Map<String, String>>value(GraphStateKeys.GUARD_REASONS)
                    .orElse(Map.of());

            Map<String, String> outputs = new LinkedHashMap<>();
            String firstOutput = "";
            // 子智能体（单发/批量）执行时消耗的 token，收尾时累加进父级账。
            // 用数组是因为要在 lambda 里改值、又要跨工具调用累积；这里单线程执行，无需原子量。
            int[] childTokens = {0};

            // 工具次数预算：跨轮累计，用尽后只回拒绝文本、不再真正执行。
            // 这是独立于轮数的闸门——模型在一轮里可能并发调多个工具，只限轮数挡不住。
            int toolCallCount = state.value(GraphStateKeys.TOOL_CALL_COUNT, 0);
            int maxToolCalls = state.value(GraphStateKeys.MAX_TOOL_CALLS, LlmNode.DEFAULT_MAX_TOOL_CALLS);

            if (toolCalls.isEmpty()) {
                // 理论上走不到这里（条件边已保证有 toolCall），兜底执行命令键
                String command = state.value(GraphStateKeys.COMMAND, "");
                firstOutput = sshTool.execute(command, terminalSessionId);
                outputs.put("", firstOutput);
            } else {
                for (AssistantMessage.ToolCall call : toolCalls) {
                    long start = System.currentTimeMillis();
                    String verdict = verdicts.getOrDefault(call.id(), GuardNode.VERDICT_PASS);
                    String output;
                    if (GuardNode.VERDICT_CONFIRM.equals(verdict)) {
                        // 高风险但未到"绝对禁止"档：不执行，把决定权交回用户。
                        // 人工确认档：只回一句说明，命令不发给远端。
                        String reason = guardReasons.getOrDefault(call.id(), "");
                        output = "（待人工确认：该命令被判定为高风险，尚未执行。原因：" + reason
                                + "。请先向用户说明这条命令的作用与风险，得到明确同意后再重新发起。）";
                        log.warn("[tool] CONFIRM 档未执行 name={}, reason={}", call.name(), reason);
                    } else if (GuardNode.VERDICT_BLOCK.equals(verdict)) {
                        // 正常流程走不到（BLOCK 整批已走 [reject]），兜底不执行
                        output = "（已拦截：该命令被安全规则禁止执行。原因："
                                + guardReasons.getOrDefault(call.id(), "") + "）";
                        log.warn("[tool] BLOCK 档跳过 name={}", call.name());
                    } else if (toolCallCount >= maxToolCalls) {
                        // 预算用尽：不再执行，返回一句可读的收口提示。
                        // 不执行也不计入次数，避免"每个被拒的调用都推高计数"导致提示逻辑抖动。
                        output = "（工具调用次数已达上限 " + maxToolCalls
                                + " 次，本次调用未执行。请基于已有信息直接给出结论，不要再请求工具。）";
                        log.warn("[tool] 工具次数预算用尽，跳过执行 name={}, count={}/{}",
                                call.name(), toolCallCount, maxToolCalls);
                    } else {
                        // 先发 tool_call 事件（老契约 {"event":"tool_call",...,"status":"executing"}），
                        // 前端据此渲染"执行中的工具卡片"；与 [observe] 的 tool_result 成对出现。
                        emitToolCallEvent(config, call);
                        output = executeTool(call, terminalSessionId, sessionId, userId, agentName, depth, traceId,
                                hostKey, remainingTokens, childTokens, config);
                        toolCallCount++;
                    }
                    long cost = System.currentTimeMillis() - start;
                    if (firstOutput.isEmpty()) {
                        firstOutput = output;
                    }
                    outputs.put(call.id(), output);
                    log.info("[tool] name={}, cost={}ms, output={}", call.name(), cost,
                            output.length() > 200 ? output.substring(0, 200) + "..." : output);
                }
            }

            Map<String, Object> delta = new HashMap<>();
            delta.put(GraphStateKeys.TOOL_OUTPUT, firstOutput);
            delta.put(GraphStateKeys.TOOL_OUTPUTS, outputs);
            delta.put(GraphStateKeys.TOOL_CALL_COUNT, toolCallCount);
            if (childTokens[0] > 0) {
                // 子图消耗并入父级总量：父级的 MAX_TOTAL_TOKENS 才管得住"派发一次子任务"的代价。
                int parentTotal = state.value(GraphStateKeys.TOTAL_TOKENS, 0);
                delta.put(GraphStateKeys.TOTAL_TOKENS, parentTotal + childTokens[0]);
            }
            return delta;
        });
    }

    /**
     * 按工具名分派。
     * <p>
     * 旧实现把所有 toolCall 都当 {@code executeCommand} 解析，导致模型一旦选择
     * {@code dispatchSubAgent}，{@code parseCommand} 取不到 {@code command} 字段而返回空串，
     * 最终只得到"(空命令，未执行)" —— 子智能体永远不会被真正派发。
     * 这里按 {@code call.name()} 分流，各工具走自己的执行入口。
     */
    private String executeTool(AssistantMessage.ToolCall call, String terminalSessionId,
                               String sessionId, String userId, String agentName, int depth,
                               String traceId, String hostKey, int remainingTokens, int[] childTokens,
                               RunnableConfig config) {
        // 派发时把父请求的流式桥传下去：子智能体的工具事件要能被前端看见，
        // 而派发是同步阻塞的，父流在这段时间里没有任何 token 产出。
        TokenStreamBridge eventSink = config == null ? null
                : config.metadata("tokenSink").map(TokenStreamBridge.class::cast).orElse(null);
        // 图内置的记忆 / 知识库工具：挂载点在 LlmNode，执行点必须在这里
        String memoryResult = executeMemoryTool(call, userId, sessionId, hostKey);
        if (memoryResult != null) {
            return memoryResult;
        }
        if (TOOL_DISPATCH_SUB_AGENT.equals(call.name())) {
            if (dispatchService == null) {
                return "（派发失败：多智能体派发服务未装配）";
            }
            try {
                DispatchRequest request = JSON.parseObject(call.arguments(), DispatchRequest.class);
                if (request == null) {
                    return "（派发失败：参数解析为空）";
                }
                log.info("[tool] 派发子智能体 agent={}, task={}", request.agent(), request.task());
                DispatchResult result = dispatchService.dispatchDetailed(request.agent(), request.task(),
                        sessionId, userId, terminalSessionId, depth, traceId, hostKey, remainingTokens, eventSink);
                childTokens[0] += result.tokens();
                return result.toEnvelope();
            } catch (Exception e) {
                log.warn("[tool] 子智能体派发参数解析失败 args={}", call.arguments(), e);
                return "（派发失败：参数解析异常 - " + e.getMessage() + "）";
            }
        }
        if (TOOL_DISPATCH_SUB_AGENTS.equals(call.name())) {
            if (orchestrator == null) {
                return "（批量派发失败：批量编排器未装配）";
            }
            try {
                BatchDispatchRequest request = JSON.parseObject(call.arguments(), BatchDispatchRequest.class);
                if (request == null || request.tasks() == null) {
                    return "（批量派发失败：参数解析为空）";
                }
                log.info("[tool] 批量派发 tasks={}, maxConcurrency={}, failFast={}",
                        request.tasks().size(), request.maxConcurrency(), request.failFast());
                GraphOrchestrator.BatchResult batch = orchestrator.executeBatchDetailed(
                        sessionId, userId, terminalSessionId, depth, traceId,
                        request.tasks(), request.maxConcurrency(), request.failFast(),
                        hostKey, remainingTokens, eventSink);
                childTokens[0] += batch.tokens();
                return batch.text();
            } catch (Exception e) {
                log.warn("[tool] 批量派发参数解析失败 args={}", call.arguments(), e);
                return "（批量派发失败：参数解析异常 - " + e.getMessage() + "）";
            }
        }
        if (TOOL_EXECUTE_COMMAND.equals(call.name())) {
            return executeShellCommand(call, terminalSessionId);
        }
        // 非内置工具：先按 MCP / skills 工具执行（这才是它们的正确执行方式）
        String extraResult = executeExtraTool(call, agentName);
        if (extraResult != null) {
            return extraResult;
        }
        // 模型偶尔会自造工具名但参数里带的却是 shell 命令，这种情况下按命令执行更有用，
        // 但必须留日志，否则又变成"静默替换"。
        String command = parseCommand(call.arguments());
        if (command != null && !command.isBlank()) {
            log.warn("[tool] 未知工具名 {}，按 shell 命令执行: {}", call.name(), command);
            return sshTool.execute(command, terminalSessionId);
        }
        return "（未知工具：" + call.name() + "，未执行。可用工具：executeCommand / dispatchSubAgent / dispatchSubAgents）";
    }

    /**
     * 执行内置 SSH 命令。
     */
    private String executeShellCommand(AssistantMessage.ToolCall call, String terminalSessionId) {
        return sshTool.execute(parseCommand(call.arguments()), terminalSessionId);
    }

    /**
     * 发 {@code tool_call} 结构化事件。
     */
    private void emitToolCallEvent(RunnableConfig config, AssistantMessage.ToolCall call) {
        try {
            TokenStreamBridge bridge = config.metadata("tokenSink").map(TokenStreamBridge.class::cast).orElse(null);
            if (bridge == null) {
                return;
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event", GraphStateKeys.EVENT_TOOL_CALL);
            event.put("type", GraphStateKeys.EVENT_TOOL_CALL);
            event.put("toolCallId", call.id());
            event.put("toolName", call.name());
            event.put("args", call.arguments());
            event.put("status", "executing");
            bridge.emitEvent(event);
        } catch (Exception e) {
            log.warn("[tool] 发送 tool_call 事件失败: {}", e.getMessage());
        }
    }

    /**
     * 执行图内置的记忆 / 知识库工具。
     * <p>
     * 这四个工具的挂载点在 {@code LlmNode}，但真正执行只能发生在这里：模型发出 toolCall 后，
     * 图只会进入 [tool] 节点，没有别的代码会替它执行。两处都放在 {@link GraphMemoryToolService}
     * 里实现，就是为了避免"模型能看见工具、调用后却拿到未知工具"这种接不上线的问题。
     *
     * @return 工具结果文本；不是记忆/知识库工具时返回 null，交给后续分派
     */
    private String executeMemoryTool(AssistantMessage.ToolCall call, String userId,
                                     String sessionId, String hostKey) {
        String name = call.name();
        if (!TOOL_RECALL_MEMORY.equals(name) && !TOOL_SAVE_MEMORY.equals(name)
                && !TOOL_WIKI_SEARCH.equals(name) && !TOOL_SAVE_WIKI.equals(name)) {
            return null;
        }
        if (memoryTools == null) {
            return "（记忆 / 知识库服务未装配，本次调用未执行）";
        }
        try {
            log.info("[tool] 执行记忆/知识库工具 name={}", name);
            if (TOOL_RECALL_MEMORY.equals(name)) {
                return memoryTools.recallMemory(userId, hostKey,
                        JSON.parseObject(call.arguments(), RecallMemoryRequest.class));
            }
            if (TOOL_SAVE_MEMORY.equals(name)) {
                return memoryTools.saveMemory(userId, sessionId, hostKey,
                        JSON.parseObject(call.arguments(), SaveMemoryRequest.class));
            }
            if (TOOL_WIKI_SEARCH.equals(name)) {
                return memoryTools.wikiSearch(userId, hostKey,
                        JSON.parseObject(call.arguments(), WikiSearchRequest.class));
            }
            return memoryTools.saveWiki(userId, hostKey,
                    JSON.parseObject(call.arguments(), SaveWikiRequest.class));
        } catch (Exception e) {
            log.warn("[tool] 记忆/知识库工具执行失败 name={} args={}", name, call.arguments(), e);
            return "（工具执行失败：" + name + " - " + e.getMessage() + "）";
        }
    }

    /**
     * 执行 MCP / skills 工具。
     * <p>
     * Spring AI 的工具契约是 {@link ToolCallback#call(String)}（参数是模型给的 JSON 字符串）。
     * 之前把这些工具交给 shell 执行，必然产出空命令——这是"接错线"，不是"没配好"。
     *
     * @return 执行结果；找不到工具或执行异常时返回可读的错误文本（绝不抛进图）
     */
    private String executeExtraTool(AssistantMessage.ToolCall call, String agentName) {
        if (toolRegistry == null) {
            return null;
        }
        ToolCallback callback = toolRegistry.find(call.name(), agentName);
        if (callback == null) {
            return null;
        }
        try {
            log.info("[tool] 执行 MCP/skills 工具 name={}", call.name());
            String result = callback.call(call.arguments());
            return result == null ? "" : result;
        } catch (Exception e) {
            log.warn("[tool] MCP/skills 工具执行失败 name={}", call.name(), e);
            return "（工具执行失败：" + call.name() + " - " + e.getMessage() + "）";
        }
    }

    private String parseCommand(String arguments) {
        try {
            return JSON.parseObject(arguments).getString("command");
        } catch (Exception e) {
            return "";
        }
    }
}