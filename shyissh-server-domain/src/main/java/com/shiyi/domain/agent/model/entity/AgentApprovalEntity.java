package com.shiyi.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 危险命令人工审批单（领域实体）。
 * <p>
 * 生命周期：{@link #STATUS_PENDING} → {@link #STATUS_APPROVED} 或 {@link #STATUS_REJECTED}。
 * 没有"已执行"状态：审批只决定"放不放行"，命令执行与否由图在下一轮自己汇报，
 * 让审批单承载执行结果会把两件事耦在一起（执行失败要不要回滚审批单？）。
 *
 * @author shiyi
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentApprovalEntity {

    /** 待人工确认。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 已批准，可放行执行。 */
    public static final String STATUS_APPROVED = "APPROVED";
    /** 已驳回，命令不执行。 */
    public static final String STATUS_REJECTED = "REJECTED";

    /** 审批单号 */
    private String id;

    /** 业务会话 ID */
    private String sessionId;

    /** 发起用户 */
    private String userId;

    /** 图运行 threadId */
    private String threadId;

    /** 本轮终端会话 ID：批准后复跑接着用同一个 SSH 终端 */
    private String terminalSessionId;

    /** 入口 Agent：批准后复跑保持同一人格与工具集 */
    private String agentName;

    /** 待确认命令 */
    private String command;

    /** 触发确认的原因 */
    private String reason;

    /** 状态 */
    private String status;

    private Date createdAt;

    private Date decidedAt;

    private String decidedBy;
}