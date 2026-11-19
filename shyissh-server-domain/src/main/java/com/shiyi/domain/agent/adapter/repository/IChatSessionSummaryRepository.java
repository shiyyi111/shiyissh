package com.shiyi.domain.agent.adapter.repository;

import com.shiyi.domain.agent.model.entity.ChatSessionSummaryEntity;

import java.util.List;

/**
 * 会话摘要仓储端口。
 * <p>
 * 写入一律用"游标条件更新"，不用"读-改-写"：同一会话并发两个请求时，
 * 后到的那个 {@code WHERE covered_until_id = 期望值} 会更新 0 行，调用方据此知道
 * "别人已经推进过游标"，直接复用库里的结果即可（幂等，不需要加锁）。
 *
 * @author shiyi
 */
public interface IChatSessionSummaryRepository {

    /**
     * 按会话查摘要。
     *
     * @param sessionId 会话 ID
     * @return 摘要实体；不存在返回 {@code null}
     */
    ChatSessionSummaryEntity findBySessionId(String sessionId);

    /**
     * 首次写入（游标从 0 开始）。
     *
     * @param entity 摘要实体
     * @return {@code true} 写入成功；{@code false} 已存在（并发抢先），调用方应重读
     */
    boolean insertIfAbsent(ChatSessionSummaryEntity entity);

    /**
     * 游标条件更新：只有库里的 {@code covered_until_id} 仍等于期望值时才覆盖。
     *
     * @param entity    新的摘要内容（包含新的 coveredUntilId 与 increments）
     * @param expected  期望的旧游标
     * @return {@code true} 更新成功；{@code false} 游标已被别人推进，本次放弃
     */
    boolean updateIfCursor(ChatSessionSummaryEntity entity, long expected);

    /**
     * 查某个用户的历史会话摘要（跨会话回忆用），按更新时间倒序。
     *
     * @param userId           用户 ID
     * @param excludeSessionId 要排除的会话（当前会话自身）
     * @param limit            最多返回条数
     * @return 摘要列表，可能为空
     */
    List<ChatSessionSummaryEntity> findRecentByUser(String userId, String excludeSessionId, int limit);
}