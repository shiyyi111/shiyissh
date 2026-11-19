package com.shiyi.infrastructure.adapter.repository;

import com.shiyi.domain.agent.adapter.repository.IAgentApprovalRepository;
import com.shiyi.domain.agent.model.entity.AgentApprovalEntity;
import com.shiyi.infrastructure.dao.IAgentApprovalDao;
import com.shiyi.infrastructure.dao.po.AgentApprovalPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 人工审批单仓储实现（PO ↔ Entity 转换 + 时间类型转换）。
 *
 * @author shiyi
 */
@Repository
public class AgentApprovalRepository implements IAgentApprovalRepository {

    @Resource
    private IAgentApprovalDao agentApprovalDao;

    @Override
    public void save(AgentApprovalEntity entity) {
        if (entity == null || entity.getId() == null) {
            return;
        }
        agentApprovalDao.insert(toPO(entity));
    }

    @Override
    public AgentApprovalEntity queryById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return toEntity(agentApprovalDao.queryById(id));
    }

    @Override
    public boolean decide(String id, String status, String decidedBy) {
        if (id == null || id.isBlank() || status == null || status.isBlank()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        return agentApprovalDao.updateDecision(id, status, now, decidedBy == null ? "" : decidedBy) > 0;
    }

    @Override
    public List<AgentApprovalEntity> queryPendingBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<AgentApprovalPO> rows = agentApprovalDao.queryPendingBySession(sessionId);
        List<AgentApprovalEntity> result = new ArrayList<>();
        if (rows != null) {
            for (AgentApprovalPO row : rows) {
                AgentApprovalEntity entity = toEntity(row);
                if (entity != null) {
                    result.add(entity);
                }
            }
        }
        return result;
    }

    private AgentApprovalPO toPO(AgentApprovalEntity entity) {
        return AgentApprovalPO.builder()
                .id(entity.getId())
                .sessionId(entity.getSessionId())
                .userId(entity.getUserId())
                .threadId(entity.getThreadId())
                .terminalSessionId(entity.getTerminalSessionId())
                .agentName(entity.getAgentName())
                .command(entity.getCommand())
                .reason(entity.getReason())
                .status(entity.getStatus())
                // 库列 NOT NULL DEFAULT CURRENT_TIMESTAMP，这里显式给值，保证插入后立刻可查
                .createdAt(entity.getCreatedAt() == null
                        ? LocalDateTime.now() : toLocalDateTime(entity.getCreatedAt()))
                .decidedAt(entity.getDecidedAt() == null ? null : toLocalDateTime(entity.getDecidedAt()))
                .decidedBy(entity.getDecidedBy() == null ? "" : entity.getDecidedBy())
                .build();
    }

    private AgentApprovalEntity toEntity(AgentApprovalPO po) {
        if (po == null) {
            return null;
        }
        return AgentApprovalEntity.builder()
                .id(po.getId())
                .sessionId(po.getSessionId())
                .userId(po.getUserId())
                .threadId(po.getThreadId())
                .terminalSessionId(po.getTerminalSessionId())
                .agentName(po.getAgentName())
                .command(po.getCommand())
                .reason(po.getReason())
                .status(po.getStatus())
                .createdAt(toDate(po.getCreatedAt()))
                .decidedAt(toDate(po.getDecidedAt()))
                .decidedBy(po.getDecidedBy())
                .build();
    }

    private static LocalDateTime toLocalDateTime(Date date) {
        return date == null ? null
                : LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }

    private static Date toDate(LocalDateTime dateTime) {
        return dateTime == null ? null
                : Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant());
    }
}