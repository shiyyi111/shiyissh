package com.shiyi.domain.agent.adapter.repository;

import com.shiyi.domain.agent.model.entity.LongTermMemoryEntity;

import java.util.List;

/**
 * 长期记忆持久化仓储接口（领域层抽象）
 * <p>
 * 两个方向：
 * <ul>
 *   <li><b>写</b>：{@link #saveOrUpdate} 采用"先查后插/更新"的 Upsert，
 *       唯一键是 (userId, scope, hostKey, memoryType, memoryKey)。
 *       同一偏好/事实不会重复入库，而是通过 hit_count 表达被提及次数。</li>
 *   <li><b>读</b>：{@link #queryRecallCandidates} 返回三个候选池的并集，
 *       真正的打分排序在 {@code LongTermMemoryService} 完成；
 *       {@link #touchUsage} 把被召回条目的 hit_count+1、last_used_at 刷新，
 *       让"常被用到的记忆"在后续召回里更容易浮上来。</li>
 * </ul>
 *
 * @see com.shiyi.infrastructure.adapter.repository.LongTermMemoryRepository
 */
public interface ILongTermMemoryRepository {

    /**
     * 保存或更新长期记忆（Upsert 模式）。
     * <p>
     * 按唯一键 (userId, scope, hostKey, memoryType, memoryKey) 查询：
     * <ul>
     *   <li>不存在 → INSERT 新记忆</li>
     *   <li>已存在 → UPDATE content/keywords/confidence/importance 等字段，hit_count + 1</li>
     * </ul>
     *
     * @param memoryEntity 长期记忆实体
     */
    /**
     * 新增或更新一条记忆。
     *
     * @return 记忆主键；新增时是自增 id，已存在时是原 id，失败返回 null
     */
    Long saveOrUpdate(LongTermMemoryEntity memoryEntity);

    /**
     * 召回候选池：返回三个池的并集（按 id 去重，未过期）。
     * <ol>
     *   <li>该主机上的全部 HOST 作用域记忆——"这台机器的 Redis 版本"只在这台机器有意义</li>
     *   <li>用户偏好全部——偏好跨主机通用（"我习惯用 vim"）</li>
     *   <li>最近更新的记忆——兜底跨主机的近期上下文</li>
     * </ol>
     *
     * @param userId  用户 ID
     * @param hostKey 当前主机标识，为空时只取后两个池
     * @return 候选记忆列表（未排序，由 Service 层打分）
     */
    List<LongTermMemoryEntity> queryRecallCandidates(String userId, String hostKey);

    /**
     * 按主键批量取回记忆真值（语义召回用：Qdrant 只给候选 id 与相关度，
     * 内容、置信度、重要度等打分字段仍以 MySQL 为准）。
     * <p>
     * 返回顺序与入参一致，调用方可直接按下标与相关度对齐；已过期或被删除的条目自动跳过。
     *
     * @param ids 候选主键列表
     * @return 存在的记忆列表（顺序同入参）
     */
    List<LongTermMemoryEntity> findByIds(List<Long> ids);

    /**
     * 索引任务捞取：待索引(0) 与 可重试失败(2)，且尝试次数未达上限。
     *
     * @param limit 单批上限
     * @return 待向量化的记忆列表
     */
    List<LongTermMemoryEntity> findPendingEmbedding(int limit);

    /**
     * 标记某条记忆已成功写入向量索引。
     *
     * @param id 记忆主键
     */
    void markEmbedded(Long id);

    /**
     * 标记某条记忆索引失败（尝试次数 +1，达到上限后不再重试）。
     *
     * @param id 记忆主键
     */
    void markEmbeddingFailed(Long id);

    /**
     * 命中回写：被召回的条目 hit_count + 1、last_used_at = now。
     *
     * @param ids 被召回的记录主键
     */
    void touchUsage(List<Long> ids);
}