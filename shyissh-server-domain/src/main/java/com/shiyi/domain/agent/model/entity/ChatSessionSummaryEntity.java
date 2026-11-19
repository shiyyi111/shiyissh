package com.shiyi.domain.agent.model.entity;

import java.util.Date;

/**
 * 会话累计摘要实体。
 * <p>
 * 一条会话一行：摘要持续覆盖"已经滑出上下文窗口"的那部分历史，靠 {@code coveredUntilId}
 * 记住覆盖到哪一条消息。这样下一轮只要拿"id 大于游标"的新滑出片段做增量，
 * 而不是把整段历史重新算一遍。
 * <p>
 * 为什么需要 {@code increments}：增量摘要有误差累积（每轮只看到"旧摘要 + 新增片段"，
 * 早期细节会一轮轮被压掉），所以每累计 N 次就要用"全量被丢弃消息"重算一次（压实）。
 * 计的是"自上次全量重算以来做过几次增量"。
 *
 * @author shiyi
 */
public class ChatSessionSummaryEntity {

    /** 会话 ID（主键） */
    private String sessionId;

    /** 会话归属用户：跨会话回忆靠它做隔离 */
    private String userId;

    /** 累计摘要正文 */
    private String summary;

    /** 已覆盖的最大消息 id（chat_message.id），0 表示尚未覆盖任何消息 */
    private Long coveredUntilId;

    /** 自上次全量重算以来的增量次数 */
    private Integer increments;

    /** 更新时间 */
    private Date updatedAt;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Long getCoveredUntilId() {
        return coveredUntilId;
    }

    public void setCoveredUntilId(Long coveredUntilId) {
        this.coveredUntilId = coveredUntilId;
    }

    public Integer getIncrements() {
        return increments;
    }

    public void setIncrements(Integer increments) {
        this.increments = increments;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }
}