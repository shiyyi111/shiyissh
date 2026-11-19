package com.shiyi.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 对话消息实体（领域层）
 * <p>
 * 对应数据库 {@code chat_message} 表，记录 ReAct 循环中每一条消息（user/assistant/tool/system）。
 * 由 {@code [intent]} / {@code [answer]}（user/assistant）与 {@code [observe]}（tool）在消息流转时落库。
 * <p>
 * 关键字段说明：
 * <ul>
 *   <li>{@code role} - 复用 OpenAI 消息角色规范：user / assistant / tool / system</li>
 *   <li>{@code tokenCount} - 预估 token 数（统一走 {@code TokenEstimator}：中文一字约一 token、英文四字符约一 token），用于上下文裁剪预算</li>
 * </ul>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatMessageEntity {

    /** 自增主键 */
    private Long id;

    /** 会话 ID（业务会话唯一标识，UUID） */
    private String sessionId;

    /** 消息角色：user / assistant / tool / system */
    private String role;

    /** 消息内容 */
    private String content;

    /** 工具名称（仅 role=tool 时有值，如 "executeCommand"） */
    private String toolName;

    /** 工具调用 ID（仅 role=tool 时有值，用于关联 assistant 的 tool_call） */
    private String toolCallId;

    /** 预估 token 数（统一走 {@code TokenEstimator} 口径） */
    private Integer tokenCount;

    /** 创建时间 */
    private Date createdAt;
}
