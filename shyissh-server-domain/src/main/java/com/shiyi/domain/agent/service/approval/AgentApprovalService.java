package com.shiyi.domain.agent.service.approval;

import com.shiyi.domain.agent.adapter.repository.IAgentApprovalRepository;
import com.shiyi.domain.agent.model.entity.AgentApprovalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 人工审批服务：把"命令需要人确认"这件事落成可追踪、可决策的单据。
 * <p>
 * 为什么要有这一层，而不是 GuardNode 直接写库：
 * <ul>
 *   <li>审批是<b>跨请求</b>的：确认动作发生在下一次 HTTP 请求里，节点是短生命周期对象，
 *       让节点持有持久化职责会把"图"和"Web 入口"耦在一起；</li>
 *   <li>决策要幂等：同一个人点两次、或两个人同时点，只能生效一次。
 *       幂等条件压在 SQL 的 {@code status='PENDING'} 上，服务层只负责把结果翻译成人话。</li>
 * </ul>
 *
 * @author shiyi
 */
@Service
public class AgentApprovalService {

    private static final Logger log = LoggerFactory.getLogger(AgentApprovalService.class);

    /** 命令原文在提示语里的展示上限，避免一条超长命令把回答撑爆。 */
    private static final int COMMAND_PREVIEW_CHARS = 200;

    @Resource
    private IAgentApprovalRepository agentApprovalRepository;

    /**
     * 登记一条待确认命令。
     *
     * @return 审批单；仓储不可用（单测/降级）时返回 null，调用方据此退化为"只回提示、不落单"
     */
    public AgentApprovalEntity requestApproval(String sessionId, String userId, String threadId,
                                               String terminalSessionId, String agentName,
                                               String command, String reason) {
        if (agentApprovalRepository == null || command == null || command.isBlank()) {
            return null;
        }
        AgentApprovalEntity entity = AgentApprovalEntity.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId == null ? "" : sessionId)
                .userId(userId == null ? "" : userId)
                .threadId(threadId == null ? "" : threadId)
                // 复跑时必须接着用同一个终端会话，否则"批准的这条命令"会落到别的机器上
                .terminalSessionId(terminalSessionId == null ? "" : terminalSessionId)
                .agentName(agentName == null ? "" : agentName)
                .command(command.strip())
                .reason(reason == null ? "" : reason.strip())
                .status(AgentApprovalEntity.STATUS_PENDING)
                .createdAt(new Date())
                .decidedBy("")
                .build();
        try {
            agentApprovalRepository.save(entity);
            log.info("[approval] 生成待确认单 id={}, session={}, command={}",
                    entity.getId(), entity.getSessionId(), preview(command));
            return entity;
        } catch (Exception e) {
            // 落单失败不能让图挂掉：退化成"本轮只回提示"，用户仍然看得到命令内容
            log.warn("[approval] 审批单落库失败，退化为仅提示: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 做决策。
     *
     * @param approved true=批准（下一轮放行执行），false=驳回
     * @return 决策结果；{@code changed=false} 表示单据不存在或已被处理过
     */
    public DecideResult decide(String approvalId, boolean approved, String decidedBy) {
        if (agentApprovalRepository == null || approvalId == null || approvalId.isBlank()) {
            return new DecideResult(null, false);
        }
        AgentApprovalEntity existing = agentApprovalRepository.queryById(approvalId);
        if (existing == null) {
            return new DecideResult(null, false);
        }
        String caller = decidedBy == null ? "" : decidedBy;
        String owner = existing.getUserId();
        if (owner != null && !owner.isBlank() && !caller.isBlank() && !owner.equals(caller)) {
            log.warn("[approval] 审批单 {} 归属 {} 与请求者 {} 不一致，拒绝决策", approvalId, owner, caller);
            return new DecideResult(existing, false);
        }
        // 审批人一律以服务端记录的归属用户为准，客户端传值只当线索，避免伪造 decidedBy
        String decidedBySafe = owner == null || owner.isBlank() ? caller : owner;
        String status = approved ? AgentApprovalEntity.STATUS_APPROVED : AgentApprovalEntity.STATUS_REJECTED;
        boolean changed = agentApprovalRepository.decide(approvalId, status, decidedBySafe);
        if (!changed) {
            // 已经被别人处理过：把最新状态返回给调用方，让前端提示"已处理"，而不是报错
            return new DecideResult(agentApprovalRepository.queryById(approvalId), false);
        }
        existing.setStatus(status);
        existing.setDecidedAt(new Date());
        existing.setDecidedBy(decidedBySafe);
        log.info("[approval] 审批单 {} → {}", approvalId, status);
        return new DecideResult(existing, true);
    }

    /** 查某会话下未决单（前端角标）。 */
    public List<AgentApprovalEntity> queryPending(String sessionId) {
        if (agentApprovalRepository == null) {
            return List.of();
        }
        return agentApprovalRepository.queryPendingBySession(sessionId);
    }

    /**
     * 生成给用户看的提示文本（进 {@code ANSWER}，由 [answer] 节点流式发出）。
     * <p>
     * 文案里同时给"命令原文"和"单号"：命令原文让用户知道要确认什么，
     * 单号让前端知道确认请求该带什么参数。
     */
    public String buildPrompt(AgentApprovalEntity approval) {
        if (approval == null) {
            return "这条命令涉及高风险变更，需要人工确认后才能执行。确认后请重新发起请求。";
        }
        return "这条命令需要你确认后才能执行：" + preview(approval.getCommand())
                + (approval.getReason() == null || approval.getReason().isBlank()
                ? "" : "（原因：" + approval.getReason() + "）")
                + "\n确认单号：" + approval.getId()
                + "\n如果你确认要执行，请调用审批接口放行；不放行则本轮到此为止。";
    }

    private String preview(String command) {
        if (command == null) {
            return "";
        }
        String text = command.strip();
        return text.length() <= COMMAND_PREVIEW_CHARS
                ? text
                : text.substring(0, COMMAND_PREVIEW_CHARS) + "…";
    }

    /**
     * 决策结果。
     *
     * @param approval 决策后的单据（可能为 null：单号不存在）
     * @param changed  本次调用是否真的改变了状态（false=重复决策/不存在）
     */
    public record DecideResult(AgentApprovalEntity approval, boolean changed) {
    }
}