package com.shiyi.infrastructure.dao;

import com.shiyi.infrastructure.dao.po.AgentApprovalPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 人工审批单 DAO。
 * <p>
 * 只有四个动作：插入、按单号查、按决策更新、按会话查未决单。
 * 不做删除——审批记录是审计素材，保留比删掉有用。
 */
@Mapper
public interface IAgentApprovalDao {

    /** 插入一条待确认记录。 */
    int insert(AgentApprovalPO po);

    /** 按单号查询。 */
    AgentApprovalPO queryById(@Param("id") String id);

    /**
     * 写决策结果。
     * <p>
     * 带 {@code status = 'PENDING'} 条件，是这里唯一需要"并发安全"的地方：
     * 两个人同时点确认时，只有第一个 UPDATE 会命中，第二个影响行数为 0，
     * 从而不会把同一条命令执行两遍。
     */
    int updateDecision(@Param("id") String id,
                       @Param("status") String status,
                       @Param("decidedAt") LocalDateTime decidedAt,
                       @Param("decidedBy") String decidedBy);

    /** 查某会话下所有未决单（前端展示"待确认"角标用）。 */
    List<AgentApprovalPO> queryPendingBySession(@Param("sessionId") String sessionId);
}