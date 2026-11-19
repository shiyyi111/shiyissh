package com.shiyi.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话累计摘要持久化对象（PO）。
 * <p>
 * 对应数据库 {@code chat_session_summary} 表，一条会话一行。
 *
 * @author shiyi
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatSessionSummaryPO {

    /** 会话 ID */
    private String sessionId;

    /** 会话归属用户 */
    private String userId;

    /** 累计摘要正文 */
    private String summary;

    /** 已覆盖的最大消息 id */
    private Long coveredUntilId;

    /** 自上次全量重算以来的增量次数 */
    private Integer increments;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}