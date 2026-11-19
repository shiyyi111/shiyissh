package com.shiyi.infrastructure.dao;

import com.shiyi.infrastructure.dao.po.LongTermMemoryPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 长期记忆 MyBatis DAO 接口
 * <p>
 * 对应 long_term_memory 表。两张用途各异的查询面：
 * <ul>
 *   <li>写入面：按唯一键 (userId, scope, hostKey, memoryType, memoryKey) 查重后
 *       INSERT / UPDATE，实现 Upsert</li>
 *   <li>召回面：三个候选池（该主机全部 / 用户偏好全部 / 最近更新）+ 命中回写</li>
 * </ul>
 * <p>
 * 召回面拆成三个池而不是一个 SQL，是因为它们的过滤条件不同：主机事实要按
 * host_key 收窄，偏好要跨主机全局可见，近期记忆要覆盖跨主机的新信息。
 */
@Mapper
public interface ILongTermMemoryDao {

    /**
     * 按唯一键查询是否已存在（Upsert 前置查询）。
     *
     * @param userId     用户 ID
     * @param scope      作用域 USER / HOST / SESSION
     * @param hostKey    主机标识，非 HOST 作用域传空串
     * @param memoryType 记忆类型
     * @param memoryKey  记忆去重键
     * @return 已存在的记忆 PO，不存在时返回 null
     */
    LongTermMemoryPO queryByIdentity(@Param("userId") String userId,
                                     @Param("scope") String scope,
                                     @Param("hostKey") String hostKey,
                                     @Param("memoryType") String memoryType,
                                     @Param("memoryKey") String memoryKey);

    /**
     * 插入一条新的长期记忆
     *
     * @param po 长期记忆持久化对象
     */
    void insert(LongTermMemoryPO po);

    /**
     * 更新已有的长期记忆（content/keywords/confidence/importance 等字段）
     *
     * @param po 已存在并更新了字段的持久化对象
     */
    void update(LongTermMemoryPO po);

    /**
     * 召回候选池：一次粗筛出"该用户能看到"的全部未过期记忆。
     * <p>
     * 归属口径有且只有两种：{@code scope = 'USER'}（跨主机通用）与
     * {@code scope = 'HOST' AND host_key = 本机}（只在这台机器上成立）。
     * 排序按重要度、再按更新时间，交给上层用多因子打分做精排——
     * 粗筛阶段不掺"类型""是否最近"这类业务判断，
     * 否则"既不是偏好、也不在最近 N 条、又不是本机事实"的记忆永远进不了候选。
     *
     * @param userId  用户 ID
     * @param hostKey 主机标识（空串表示当前没有绑定终端，此时只看 USER 级记忆）
     * @param limit   粗筛上限
     * @return 记忆列表
     */
    List<LongTermMemoryPO> queryCandidates(@Param("userId") String userId,
                                           @Param("hostKey") String hostKey,
                                           @Param("limit") int limit);

    /**
     * 召回候选池 2：某类记忆的全部条目（未过期），主要用于全局适用的用户偏好
     *
     * @param userId     用户 ID
     * @param memoryType 记忆类型
     * @param limit      最大返回条数
     * @return 记忆列表（按 importance DESC, updated_at DESC）
     */
    List<LongTermMemoryPO> queryByType(@Param("userId") String userId,
                                       @Param("memoryType") String memoryType,
                                       @Param("limit") int limit);

    /**
     * 召回候选池 3：按用户 ID 查询最近更新的记忆（未过期）
     *
     * @param userId 用户 ID
     * @param limit  最大返回条数
     * @return 长期记忆列表（按 updated_at DESC, id DESC 排序）
     */
    List<LongTermMemoryPO> queryRecentByUserId(@Param("userId") String userId,
                                               @Param("limit") int limit);

    /**
     * 按主键批量查询（语义召回候选回表：Qdrant 只给 id，内容与打分字段以 MySQL 为准）
     *
     * @param ids 主键列表
     * @return 未过期的记忆列表
     */
    List<LongTermMemoryPO> queryByIds(@Param("ids") List<Long> ids);

    /**
     * 索引任务捞取：待索引(0) 与 可重试失败(2)，且尝试次数未达上限
     *
     * @param limit 单批上限
     * @return 待向量化的记忆列表
     */
    List<LongTermMemoryPO> queryEmbeddingPending(@Param("limit") int limit);

    /**
     * 标记索引成功
     *
     * @param id 记忆主键
     */
    void markEmbedded(@Param("id") Long id);

    /**
     * 标记索引失败（attempts+1，达上限后不再重试）
     *
     * @param id 记忆主键
     */
    void markEmbeddingFailed(@Param("id") Long id);

    /**
     * 命中回写：把被召回的条目 hit_count+1 并刷新 last_used_at
     *
     * @param ids 被召回的记录主键
     */
    void touchUsage(@Param("ids") List<Long> ids);
}

