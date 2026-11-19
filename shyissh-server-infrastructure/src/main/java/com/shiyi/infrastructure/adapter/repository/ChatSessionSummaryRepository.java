package com.shiyi.infrastructure.adapter.repository;

import com.shiyi.domain.agent.adapter.repository.IChatSessionSummaryRepository;
import com.shiyi.domain.agent.model.entity.ChatSessionSummaryEntity;
import com.shiyi.infrastructure.dao.IChatSessionSummaryDao;
import com.shiyi.infrastructure.dao.po.ChatSessionSummaryPO;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 会话摘要仓储实现（MySQL）。
 * <p>
 * 写入语义见端口注释：首次插入用 {@code insertIfAbsent}，之后一律"游标条件更新"。
 * 这里把 {@link DuplicateKeyException} 也翻译成 {@code false}，让并发冲突只有一种处理方式。
 *
 * @author shiyi
 */
@Repository
public class ChatSessionSummaryRepository implements IChatSessionSummaryRepository {

    @Resource
    private IChatSessionSummaryDao chatSessionSummaryDao;

    @Override
    public ChatSessionSummaryEntity findBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return toEntity(chatSessionSummaryDao.queryBySessionId(sessionId));
    }

    @Override
    public boolean insertIfAbsent(ChatSessionSummaryEntity entity) {
        try {
            return chatSessionSummaryDao.insert(toPO(entity)) > 0;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public boolean updateIfCursor(ChatSessionSummaryEntity entity, long expected) {
        return chatSessionSummaryDao.updateIfCursor(toPO(entity), expected) > 0;
    }

    @Override
    public List<ChatSessionSummaryEntity> findRecentByUser(String userId, String excludeSessionId, int limit) {
        if (userId == null || userId.isBlank() || limit <= 0) {
            return List.of();
        }
        List<ChatSessionSummaryPO> rows = chatSessionSummaryDao.queryRecentByUser(
                userId, excludeSessionId == null ? "" : excludeSessionId, limit);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<ChatSessionSummaryEntity> result = new ArrayList<>(rows.size());
        for (ChatSessionSummaryPO row : rows) {
            ChatSessionSummaryEntity entity = toEntity(row);
            if (entity != null) {
                result.add(entity);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private ChatSessionSummaryPO toPO(ChatSessionSummaryEntity entity) {
        return ChatSessionSummaryPO.builder()
                .sessionId(entity.getSessionId())
                .userId(entity.getUserId() == null ? "" : entity.getUserId())
                .summary(entity.getSummary())
                .coveredUntilId(entity.getCoveredUntilId() == null ? 0L : entity.getCoveredUntilId())
                .increments(entity.getIncrements() == null ? 0 : entity.getIncrements())
                .build();
    }

    private ChatSessionSummaryEntity toEntity(ChatSessionSummaryPO po) {
        if (po == null) {
            return null;
        }
        ChatSessionSummaryEntity entity = new ChatSessionSummaryEntity();
        entity.setSessionId(po.getSessionId());
        entity.setUserId(po.getUserId());
        entity.setSummary(po.getSummary());
        entity.setCoveredUntilId(po.getCoveredUntilId());
        entity.setIncrements(po.getIncrements());
        entity.setUpdatedAt(po.getUpdatedAt() == null ? null : Date.from(
                po.getUpdatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant()));
        return entity;
    }
}