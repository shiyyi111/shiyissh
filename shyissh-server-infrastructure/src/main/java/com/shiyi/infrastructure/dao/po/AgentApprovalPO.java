package com.shiyi.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 危险命令人工审批单（PO）。
 * <p>
 * 对应 {@code agent_approval} 表。为什么需要它：
 * <pre>
 *   CommandGuard 判定某条命令是 CONFIRM（如 systemctl restart nginx），
 *   图不能自己决定"执行还是不执行"——需要人看一眼。
 * </pre>
 * 这张表就是"人看一眼"的载体：图先落一条 PENDING，把审批单号回给前端；
 * 用户点确认后，后端按单号把命令放行，重新发起一次图执行。
 * <p>
 * 为什么不用 SAA 的 {@code interruptBefore("tool")} + checkpoint：那套要依赖
 * 持久化 Saver（建表、序列化整个 state），对一个"只确认一条命令"的场景过重；
 * 用业务表记录 + 二次执行，语义更直白，也不怕 checkpoint 版本升级。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentApprovalPO {

    /** 审批单号（UUID） */
    private String id;

    /** 业务会话 ID */
    private String sessionId;

    /** 发起用户 */
    private String userId;

    /** 图运行的 threadId（便于把本单和某次图执行对上） */
    private String threadId;

    /** 本轮终端会话 ID：批准后复跑要接着用同一个 SSH 终端，否则命令会落到别的机器 */
    private String terminalSessionId;

    /** 入口 Agent：批准后复跑保持同一个 Agent 人格与工具集 */
    private String agentName;

    /** 待确认的原始命令 */
    private String command;

    /** 触发确认的规则原因（CommandGuard 给的话术） */
    private String reason;

    /** PENDING / APPROVED / REJECTED / EXPIRED */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime decidedAt;

    /** 决策人 */
    private String decidedBy;
}