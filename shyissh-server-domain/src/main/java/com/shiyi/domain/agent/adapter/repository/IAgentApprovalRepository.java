package com.shiyi.domain.agent.adapter.repository;

import com.shiyi.domain.agent.model.entity.AgentApprovalEntity;

import java.util.List;

/**
 * 人工审批单仓储（领域侧端口）。
 *
 * @author shiyi
 */
public interface IAgentApprovalRepository {

    /** 落一条待确认单。 */
    void save(AgentApprovalEntity entity);

    /** 按单号查；不存在返回 null。 */
    AgentApprovalEntity queryById(String id);

    /**
     * 写决策结果。
     * <p>
     * 返回是否真的改到了：只有当前状态仍是 PENDING 才会更新成功，
     * 调用方据此判断"是不是被别人抢先处理了"，避免同一命令被执行两次。
     */
    boolean decide(String id, String status, String decidedBy);

    /** 查某会话下的未决单。 */
    List<AgentApprovalEntity> queryPendingBySession(String sessionId);
}