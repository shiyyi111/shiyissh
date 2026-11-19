package com.shiyi.infrastructure.dao;

import com.shiyi.infrastructure.dao.po.ChatSessionSummaryPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 会话累计摘要 MyBatis DAO 接口。
 * <p>
 * 对应 {@code chat_session_summary} 表。写入分三种：首次插入、游标条件更新、
 * 以及跨会话回忆用的按用户查询。
 *
 * @author shiyi
 */
@Mapper
public interface IChatSessionSummaryDao {

    /**
     * 按会话查摘要。
     *
     * @param sessionId 会话 ID
     * @return 摘要 PO；不存在返回 {@code null}
     */
    ChatSessionSummaryPO queryBySessionId(@Param("sessionId") String sessionId);

    /**
     * 首次插入。
     *
     * @param po 摘要 PO
     * @return 影响行数（1 = 成功，0 = 已存在）
     */
    int insert(ChatSessionSummaryPO po);

    /**
     * 游标条件更新：{@code WHERE session_id = ? AND covered_until_id = ?}。
     *
     * @param po       新的摘要内容
     * @param expected 期望的旧游标
     * @return 影响行数（1 = 推进成功，0 = 游标已被别人推进）
     */
    int updateIfCursor(@Param("po") ChatSessionSummaryPO po, @Param("expected") long expected);

    /**
     * 查某个用户最近更新的其他会话摘要。
     *
     * @param userId           用户 ID
     * @param excludeSessionId 排除的会话
     * @param limit            最大条数
     * @return 摘要列表（按 updated_at DESC）
     */
    List<ChatSessionSummaryPO> queryRecentByUser(@Param("userId") String userId,
                                                 @Param("excludeSessionId") String excludeSessionId,
                                                 @Param("limit") int limit);
}