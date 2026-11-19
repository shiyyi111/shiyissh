package com.shiyi.infrastructure.adapter.repository;

import com.shiyi.domain.agent.adapter.repository.ILongTermMemoryRepository;
import com.shiyi.domain.agent.model.entity.LongTermMemoryEntity;
import com.shiyi.infrastructure.dao.ILongTermMemoryDao;
import com.shiyi.infrastructure.dao.po.LongTermMemoryPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 长期记忆仓储实现（基础设施层）
 * <p>
 * 实现 {@link ILongTermMemoryRepository}，通过 MyBatis DAO 操作数据库。
 * <p>
 * 两个方向的关键逻辑：
 * <ul>
 *   <li>{@link #saveOrUpdate} —— "先查后插/更新"，按 (userId, scope, hostKey,
 *       memoryType, memoryKey) 判定是否已存在；已存在时 hit_count+1</li>
 *   <li>{@link #queryRecallCandidates} —— 三个候选池合并去重：该主机事实、
 *       用户偏好、最近更新。三个池的过滤条件不同，所以拆成三条 SQL</li>
 * </ul>
 * <p>
 * 为什么不写一条大 SQL？因为"该主机"与"全局偏好"是两个互斥的过滤维度：
 * HOST 池必须 host_key 相等，偏好池必须忽略 host_key。合并放在这里，
 * 打分排序留在 Service 层。
 *
 * @see ILongTermMemoryRepository
 */
@Repository
public class LongTermMemoryRepository implements ILongTermMemoryRepository {

    /** 作用域常量：跨主机通用 */
    private static final String SCOPE_USER = "USER";

    /** 作用域常量：绑定某台主机 */
    private static final String SCOPE_HOST = "HOST";

    /** 记忆类型常量：用户偏好（全局适用） */
    private static final String TYPE_USER_PREFERENCE = "USER_PREFERENCE";

    /** 粗筛上限：只防极端数据量，正常用户的记忆远少于此 */
    private static final int CANDIDATE_POOL_LIMIT = 300;

    /** 单台主机的候选记忆上限：正常一台机器不会有几百条环境事实 */

    /** 用户偏好的候选上限 */

    /** 近期记忆候选上限 */

    /** 默认重要度 */
    private static final double DEFAULT_IMPORTANCE = 0.5d;

    @Resource
    private ILongTermMemoryDao longTermMemoryDao;

    /**
     * {@inheritDoc}
     * <p>
     * Upsert 流程：按唯一键查询 → 不存在则 INSERT → 已存在则 UPDATE + hit_count+1。
     */
    @Override
    public Long saveOrUpdate(LongTermMemoryEntity memoryEntity) {
        String scope = normalizeScope(memoryEntity.getScope());
        String hostKey = SCOPE_HOST.equals(scope) ? nullSafe(memoryEntity.getHostKey()) : "";

        LongTermMemoryPO exist = longTermMemoryDao.queryByIdentity(
                memoryEntity.getUserId(),
                scope,
                hostKey,
                memoryEntity.getMemoryType(),
                memoryEntity.getMemoryKey()
        );

        if (exist == null) {
            LongTermMemoryPO po = toPO(memoryEntity);
            po.setScope(scope);
            po.setHostKey(hostKey);
            po.setHitCount(po.getHitCount() == null ? 1 : po.getHitCount());
            po.setImportance(po.getImportance() == null ? DEFAULT_IMPORTANCE : po.getImportance());
            longTermMemoryDao.insert(po);
            return po.getId();
        }

        exist.setSessionId(memoryEntity.getSessionId());
        exist.setContent(memoryEntity.getContent());
        exist.setKeywords(memoryEntity.getKeywords());
        exist.setSourceRole(memoryEntity.getSourceRole());
        exist.setConfidence(memoryEntity.getConfidence());
        if (memoryEntity.getImportance() != null) {
            exist.setImportance(memoryEntity.getImportance());
        }
        exist.setExpiresAt(memoryEntity.getExpiresAt() == null
                ? exist.getExpiresAt()
                : toLocalDateTime(memoryEntity.getExpiresAt()));
        exist.setHitCount((exist.getHitCount() == null ? 0 : exist.getHitCount()) + 1);
        longTermMemoryDao.update(exist);
        return exist.getId();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 三个池按 id 合并去重，保持"主机池 → 偏好池 → 近期池"的插入顺序。
     */
    @Override
    public List<LongTermMemoryEntity> queryRecallCandidates(String userId, String hostKey) {
        if (isBlank(userId)) {
            return Collections.emptyList();
        }

        // 一次粗筛：该用户能看到的全部未过期记忆（USER 级 + 本机 HOST 级），
        // 上限只用来挡住"这个用户记了几万条"的极端情况，精排由上层打分决定。
        String host = isBlank(hostKey) ? "" : hostKey.trim();
        return longTermMemoryDao.queryCandidates(userId, host, CANDIDATE_POOL_LIMIT)
                .stream()
                .map(this::toEntity)
                .toList();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 保持入参顺序：Qdrant 的相关度排序就是候选顺序，回表后不能按主键重排，
     * 否则"语义最像的那条"会被打散，覆盖率归一化也会失真。
     */
    @Override
    public List<LongTermMemoryEntity> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Collections.emptyList();
        }
        java.util.Map<Long, LongTermMemoryEntity> byId = longTermMemoryDao.queryByIds(distinct).stream()
                .collect(Collectors.toMap(LongTermMemoryPO::getId, this::toEntity, (left, right) -> left));
        List<LongTermMemoryEntity> ordered = new ArrayList<>(distinct.size());
        for (Long id : distinct) {
            LongTermMemoryEntity entity = byId.get(id);
            if (entity != null) {
                ordered.add(entity);
            }
        }
        return ordered;
    }

    @Override
    public List<LongTermMemoryEntity> findPendingEmbedding(int limit) {
        int size = limit <= 0 ? 50 : limit;
        return longTermMemoryDao.queryEmbeddingPending(size).stream().map(this::toEntity).toList();
    }

    @Override
    public void markEmbedded(Long id) {
        if (id != null) {
            longTermMemoryDao.markEmbedded(id);
        }
    }

    @Override
    public void markEmbeddingFailed(Long id) {
        if (id != null) {
            longTermMemoryDao.markEmbeddingFailed(id);
        }
    }

    @Override
    public void touchUsage(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        longTermMemoryDao.touchUsage(ids);
    }

    // ==================== PO ↔ Entity 转换 ====================

    private LongTermMemoryPO toPO(LongTermMemoryEntity entity) {
        return LongTermMemoryPO.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .scope(entity.getScope())
                .hostKey(entity.getHostKey())
                .sessionId(entity.getSessionId())
                .memoryType(entity.getMemoryType())
                .memoryKey(entity.getMemoryKey())
                .content(entity.getContent())
                .keywords(entity.getKeywords())
                .sourceRole(entity.getSourceRole())
                .confidence(entity.getConfidence())
                .importance(entity.getImportance())
                .hitCount(entity.getHitCount())
                .lastUsedAt(toLocalDateTime(entity.getLastUsedAt()))
                .expiresAt(toLocalDateTime(entity.getExpiresAt()))
                .embeddingStatus(entity.getEmbeddingStatus() == null ? 0 : entity.getEmbeddingStatus())
                .embeddingAttempts(entity.getEmbeddingAttempts() == null ? 0 : entity.getEmbeddingAttempts())
                .embeddingUpdatedAt(toLocalDateTime(entity.getEmbeddingUpdatedAt()))
                .build();
    }

    private LongTermMemoryEntity toEntity(LongTermMemoryPO po) {
        return LongTermMemoryEntity.builder()
                .id(po.getId())
                .userId(po.getUserId())
                .scope(po.getScope())
                .hostKey(po.getHostKey())
                .sessionId(po.getSessionId())
                .memoryType(po.getMemoryType())
                .memoryKey(po.getMemoryKey())
                .content(po.getContent())
                .keywords(po.getKeywords())
                .sourceRole(po.getSourceRole())
                .confidence(po.getConfidence())
                .importance(po.getImportance())
                .hitCount(po.getHitCount())
                .lastUsedAt(toDate(po.getLastUsedAt()))
                .expiresAt(toDate(po.getExpiresAt()))
                .createdAt(toDate(po.getCreatedAt()))
                .updatedAt(toDate(po.getUpdatedAt()))
                .embeddingStatus(po.getEmbeddingStatus())
                .embeddingAttempts(po.getEmbeddingAttempts())
                .embeddingUpdatedAt(toDate(po.getEmbeddingUpdatedAt()))
                .build();
    }

    private static Timestamp toTimestamp(Date date) {
        return date == null ? null : new Timestamp(date.getTime());
    }

    private static java.time.LocalDateTime toLocalDateTime(Date date) {
        Timestamp timestamp = toTimestamp(date);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static Date toDate(java.time.LocalDateTime dateTime) {
        return dateTime == null ? null : new Date(Timestamp.valueOf(dateTime).getTime());
    }

    private static String normalizeScope(String scope) {
        return SCOPE_HOST.equals(scope) ? SCOPE_HOST : SCOPE_USER;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
