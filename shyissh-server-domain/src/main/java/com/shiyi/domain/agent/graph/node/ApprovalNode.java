package com.shiyi.domain.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.fastjson2.JSON;
import com.shiyi.domain.agent.graph.GraphStateKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [approval] 人工审批节点。
 * <p>
 * 位置：{@code [guard] --CONFIRM--> ⏸ 断点 --> [approval] --> [tool]（批准）或 [answer]（驳回）}。
 * <p>
 * 同一个节点承担两件事，靠 {@link GraphStateKeys#APPROVAL_DECISION} 区分：
 * <ol>
 *   <li><b>没有裁决值</b>（正常首次执行）：说明容器没配检查点、断点没生效。
 *       此时它把 [guard] 写好的提示文本搬到 {@code ANSWER}，标记 {@code need_approval} 收口，
 *       用户确认后由上层重新发起请求（旧做法，作为没有检查点时的兜底）；</li>
 *   <li><b>有裁决值</b>（断点重续）：说明这条执行是从检查点恢复的。
 *       批准则把 verdict 改成 PASS，条件边把流程送回 [tool] 真正执行那条命令；
 *       驳回则写一句明确的取消说明并收口，不再走工具。</li>
 * </ol>
 * 为什么用 verdict 而不是新加一条边来区分：{@code GUARD_VERDICT} 本来就是"这一轮该怎么走"的唯一开关，
 * 复用它可以避免图上出现两条通往 [tool] 的边、以及随之而来的"到底哪条生效"的歧义。
 *
 * @author shiyi
 */
@Component
public class ApprovalNode implements GraphNode {

    private static final Logger log = LoggerFactory.getLogger(ApprovalNode.class);

    /** 收口原因：等待人工审批（与 completed/max_rounds 等并列，前端据此渲染确认按钮）。 */
    public static final String STOP_REASON_NEED_APPROVAL = "need_approval";

    /** 收口原因：人工驳回，命令未执行。 */
    public static final String STOP_REASON_APPROVAL_REJECTED = "approval_rejected";

    private static final String FALLBACK_PROMPT =
            "这条命令涉及高风险变更，需要人工确认后才能执行。确认后请重新发起请求。";

    private static final String REJECTED_ANSWER =
            "好的，这条命令已取消，没有在你的服务器上执行。如果仍然需要处理这个问题，可以告诉我换一种更安全的方式。";

    @Override
    public String name() {
        return GraphStateKeys.NODE_APPROVAL;
    }

    /**
     * 把本批每一条工具调用的判定都改成 PASS。
     * <p>
     * 为什么不能只改顶层 {@code GUARD_VERDICT}：[tool] 节点是逐条看 {@code GUARD_VERDICTS} 决定执行还是跳过的，
     * 顶层那个只决定"这一轮走不走 [tool]"。只改顶层会让流程进了 [tool]、但每条命令仍被判为 CONFIRM 而不执行，
     * 表现为"批了但什么也没做"。
     */
    /**
     * 只放行用户真正批准的那一条，同批次其余命令一律拦下。
     * <p>
     * 审批弹窗只展示了被拦下的那条命令；用户点确认表达的是这条可以执行，
     * 不是这一整批都可以执行。整批置 PASS 等于用一次点击放行了没被展示的命令。
     */
    private Map<String, String> approveAll(OverAllState state, String approvedCommand) {
        Map<String, String> verdicts = new HashMap<>();
        String approved = approvedCommand == null ? "" : approvedCommand.strip();
        state.<List<AssistantMessage.ToolCall>>value(GraphStateKeys.TOOL_CALLS).orElse(List.of())
                .forEach(call -> verdicts.put(call.id(),
                        approvedMatches(call, approved) ? GuardNode.VERDICT_PASS : GuardNode.VERDICT_BLOCK));
        return verdicts;
    }

    /** 这条工具调用的命令是否就是用户批准的那一条（按命令原文比较，防止搭便车）。 */
    private boolean approvedMatches(AssistantMessage.ToolCall call, String approved) {
        if (approved.isEmpty() || call == null) {
            return false;
        }
        String command;
        try {
            command = JSON.parseObject(call.arguments()).getString("command");
        } catch (Exception e) {
            return false;
        }
        return command != null && approved.equals(command.strip());
    }

    @Override
    public AsyncNodeActionWithConfig action() {
        return AsyncNodeActionWithConfig.node_async((OverAllState state, RunnableConfig config) -> {
            String decision = state.value(GraphStateKeys.APPROVAL_DECISION, "");
            String approvalId = state.value(GraphStateKeys.APPROVAL_ID, "");
            String command = state.value(GraphStateKeys.COMMAND, "");

            Map<String, Object> delta = new HashMap<>();
            if (GraphStateKeys.APPROVAL_APPROVED.equals(decision)) {
                // 批准：本批所有命令的判定都改成 PASS，条件边送回 [tool] 执行
                log.info("[approval] 人工批准，继续执行 approvalId={}, command={}", approvalId, command);
                delta.put(GraphStateKeys.GUARD_VERDICT, GuardNode.VERDICT_PASS);
                delta.put(GraphStateKeys.GUARD_VERDICTS, approveAll(state, command));
                delta.put(GraphStateKeys.APPROVED_COMMAND, command);
                delta.put(GraphStateKeys.STOP_REASON, "");
                return delta;
            }
            if (GraphStateKeys.APPROVAL_REJECTED.equals(decision)) {
                log.info("[approval] 人工驳回 approvalId={}, command={}", approvalId, command);
                delta.put(GraphStateKeys.GUARD_VERDICT, GuardNode.VERDICT_BLOCK);
                delta.put(GraphStateKeys.ANSWER, REJECTED_ANSWER);
                delta.put(GraphStateKeys.ANSWER_STREAMED, false);
                delta.put(GraphStateKeys.STOP_REASON, STOP_REASON_APPROVAL_REJECTED);
                return delta;
            }

            String prompt = state.value(GraphStateKeys.APPROVAL_PROMPT, "");
            if (prompt == null || prompt.isBlank()) {
                prompt = FALLBACK_PROMPT;
            }
            log.info("[approval] 收敛到人工确认（无检查点，等待重新发起），approvalId={}, command={}",
                    approvalId, command);
            // answerStreamed=false：让 [answer] 节点把这段文本当成"尚未流式输出"的内容发出去
            delta.put(GraphStateKeys.ANSWER, prompt);
            delta.put(GraphStateKeys.ANSWER_STREAMED, false);
            delta.put(GraphStateKeys.STOP_REASON, STOP_REASON_NEED_APPROVAL);
            return delta;
        });
    }
}