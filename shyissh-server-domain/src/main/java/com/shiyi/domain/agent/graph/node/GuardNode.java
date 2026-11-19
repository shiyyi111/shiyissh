package com.shiyi.domain.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.fastjson2.JSON;
import com.shiyi.domain.agent.graph.GraphStateKeys;
import com.shiyi.domain.agent.model.entity.AgentApprovalEntity;
import com.shiyi.domain.agent.service.approval.AgentApprovalService;
import com.shiyi.domain.agent.service.tool.guard.CommandGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [guard] 命令安全检查节点。
 * <pre>
 *   输入：本轮 TOOL_CALLS；输出：每个 toolCall 的 PASS / BLOCK / CONFIRM 与原因。
 *   BLOCK   → 走 [reject] 边，整批拒绝
 *   CONFIRM → 走 [approval] 边，落一张审批单、回提示，等人工决策
 *   PASS    → 走 [tool] 边，真正执行
 * </pre>
 * 判定复用 {@code CommandGuard}（22 条规则，BLOCK / CONFIRM 分级），规则集中在该类里，改规则不用动节点。
 * <p>
 * 一次模型调用可能同时返回多个工具调用（例如 {@code df -h} + {@code df -i}），
 * 所以这里逐个检查，只要有任意一个是 BLOCK，整批都不执行（走 [reject]）。
 * <p>
 * <b>人工审批怎么闭环</b>：
 * <ol>
 *   <li>CONFIRM 时本节点生成审批单（{@link GraphStateKeys#APPROVAL_ID}）并写好提示文本，图走向 [answer] 收口；</li>
 *   <li>用户确认后调用审批接口，后端把该命令写进下一次请求的
 *       {@link GraphStateKeys#APPROVED_COMMAND} 并重新发起一轮；</li>
 *   <li>本节点下次看到"命令原文与已批准命令完全一致"时判 PASS，命令才真正执行。</li>
 * </ol>
 * 按命令原文而不是按单号放行，是为了防止模型在拿到"已批准"标记后换一条命令搭便车。
 *
 * @author shiyi
 */
@Component
public class GuardNode implements GraphNode {

    private static final Logger log = LoggerFactory.getLogger(GuardNode.class);

    public static final String VERDICT_PASS = "PASS";
    public static final String VERDICT_BLOCK = "BLOCK";
    /** 需要人工确认。 */
    public static final String VERDICT_CONFIRM = "CONFIRM";

    /**
     * 审批服务：CONFIRM 时落单。
     * <p>
     * 用 {@code @Resource} 而不是构造器注入，是为了让单测能直接 {@code new GuardNode()}
     * （此时为 null，节点退化成"只回提示、不落单"，判定逻辑本身仍可测）。
     */
    @Resource
    private AgentApprovalService approvalService;

    @Override
    public String name() {
        return GraphStateKeys.NODE_GUARD;
    }

    @Override
    public AsyncNodeActionWithConfig action() {
        return AsyncNodeActionWithConfig.node_async((OverAllState state, RunnableConfig config) -> {
            List<AssistantMessage.ToolCall> toolCalls =
                    state.<List<AssistantMessage.ToolCall>>value(GraphStateKeys.TOOL_CALLS).orElse(List.of());
            if (toolCalls.isEmpty()) {
                log.warn("[guard] 没有工具调用，直接判定 PASS");
                return verdict(VERDICT_PASS, "", "", Map.of(), Map.of(), "", "");
            }

            String approvedCommand = state.value(GraphStateKeys.APPROVED_COMMAND, "");
            String sessionId = state.value(GraphStateKeys.SESSION_ID, "");
            String userId = state.value(GraphStateKeys.USER_ID, "");
            String terminalSessionId = state.value(GraphStateKeys.TERMINAL_SESSION_ID, "");
            String agentName = state.value(GraphStateKeys.AGENT_NAME, "");
            String threadId = config == null ? "" : config.threadId().orElse("");

            String firstCommand = "";
            String blockCommand = null;
            String blockReason = "";
            String confirmCommand = null;
            String confirmReason = "";
            // 逐条记录：ToolNode 只执行 PASS 的那几条，CONFIRM/BLOCK 会被替换成说明回灌给模型
            Map<String, String> verdicts = new LinkedHashMap<>();
            Map<String, String> reasons = new LinkedHashMap<>();

            for (AssistantMessage.ToolCall call : toolCalls) {
                String command = parseCommand(call.arguments());
                if (firstCommand.isBlank()) {
                    firstCommand = command;
                }
                String level;
                String reason;
                if (!approvedCommand.isBlank() && approvedCommand.strip().equals(command == null ? "" : command.strip())) {
                    // 用户已经从审批接口放行了这一条：跳过规则判定，直接执行
                    level = VERDICT_PASS;
                    reason = "已获人工批准";
                    log.info("[guard] 已批准命令放行: {}", command);
                } else {
                    CommandGuard.Verdict result = CommandGuard.inspect(command);
                    log.info("[guard] tool={}, command={}, level={}, reason={}",
                            call.name(), command, result.level(), result.reason());
                    level = switch (result.level()) {
                        case BLOCK -> VERDICT_BLOCK;
                        // CONFIRM 不当 PASS 放行：这条命令不执行，转人审，等用户决策后再执行。
                        // 当 PASS 放行的后果是 rm -rf 一类命令被直接跑掉。
                        case CONFIRM -> VERDICT_CONFIRM;
                        default -> VERDICT_PASS;
                    };
                    reason = result.reason() == null ? "" : result.reason();
                }
                verdicts.put(call.id(), level);
                reasons.put(call.id(), reason);
                if (VERDICT_BLOCK.equals(level) && blockCommand == null) {
                    blockCommand = command;
                    blockReason = reason;
                }
                if (VERDICT_CONFIRM.equals(level) && confirmCommand == null) {
                    confirmCommand = command;
                    confirmReason = reason;
                }
            }

            if (blockCommand != null) {
                // 任一命令被拦，整批都不执行：这批里还有用户没看到的命令（审批弹窗只展示被拦的那条），
                // 让它们跟着跑等于绕过审批。整批拒绝后由模型拆开重试，代价是一次模型往返。
                return verdict(VERDICT_BLOCK, blockCommand, blockReason, verdicts, reasons, "", "");
            }
            if (confirmCommand != null) {
                // 落一张审批单，并把"命令 + 原因 + 单号"的提示文本交给 [approval] 节点
                AgentApprovalEntity approval = null;
                if (approvalService != null) {
                    approval = approvalService.requestApproval(sessionId, userId, threadId,
                            terminalSessionId, agentName, confirmCommand, confirmReason);
                }
                String approvalId = approval == null ? "" : approval.getId();
                String prompt = approvalService == null ? "" : approvalService.buildPrompt(approval);
                return verdict(VERDICT_CONFIRM, confirmCommand, confirmReason, verdicts, reasons, approvalId, prompt);
            }
            return verdict(VERDICT_PASS, firstCommand, "", verdicts, reasons, "", "");
        });
    }

    private String parseCommand(String arguments) {
        try {
            return JSON.parseObject(arguments).getString("command");
        } catch (Exception e) {
            return "";
        }
    }

    private Map<String, Object> verdict(String level, String command, String reason,
                                        Map<String, String> verdicts, Map<String, String> reasons,
                                        String approvalId, String approvalPrompt) {
        Map<String, Object> delta = new HashMap<>();
        delta.put(GraphStateKeys.COMMAND, command == null ? "" : command);
        delta.put(GraphStateKeys.GUARD_VERDICT, level);
        delta.put(GraphStateKeys.GUARD_REASON, reason == null ? "" : reason);
        delta.put(GraphStateKeys.GUARD_VERDICTS, verdicts);
        delta.put(GraphStateKeys.GUARD_REASONS, reasons);
        if (approvalId != null && !approvalId.isBlank()) {
            delta.put(GraphStateKeys.APPROVAL_ID, approvalId);
            delta.put(GraphStateKeys.APPROVAL_PROMPT, approvalPrompt == null ? "" : approvalPrompt);
        }
        return delta;
    }
}