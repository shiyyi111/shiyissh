package com.shiyi.test.summary;

import com.shiyi.domain.agent.adapter.repository.IChatSessionSummaryRepository;
import com.shiyi.domain.agent.adapter.vector.VectorStore;
import com.shiyi.domain.agent.model.entity.ChatSessionSummaryEntity;
import com.shiyi.domain.agent.service.chat.SummaryVectorService;
import com.shiyi.domain.agent.service.context.provider.impl.SessionRecallProvider;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * P3 游标写入 + P4 跨会话回忆的端到端验证（真 MySQL + 真 Qdrant + 真 embedding）。
 * <p>
 * 为什么必须在 app 模块跑真栈：P3 的关键是"条件更新真的能防并发覆盖"，
 * 这是 SQL 语义（{@code WHERE covered_until_id = ?}），mock 的 MyBatis 验证不了；
 * P4 的关键是"换一种说法也能召回上一次会话的结论"，这取决于 embedding 与融合排序，
 * 同样无法 mock。
 * <p>
 * 数据隔离：固定 {@code user_id = p4-eval-user}、会话前缀 {@code p4-s-}，测试后清理 MySQL 行与 Qdrant 点。
 *
 * @author shiyi
 */
@RunWith(SpringRunner.class)
@SpringBootTest(properties = {
        "app.rag.enabled=true",
        "app.rag.vector.enabled=true",
        "app.rag.vector.store=qdrant",
        "app.rag.memory-index.enabled=false"
})
public class SummaryRecallEndToEndTest {

    private static final String USER_ID = "p4-eval-user";
    private static final String SESSION_A = "p4-s-a";
    private static final String SESSION_B = "p4-s-b";
    private static final String SESSION_CURRENT = "p4-s-current";

    @Resource
    private IChatSessionSummaryRepository summaryRepository;

    @Resource
    private SummaryVectorService summaryVectorService;

    @Resource
    private SessionRecallProvider sessionRecallProvider;

    @Resource
    private VectorStore vectorStore;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @BeforeClass
    public static void banner() {
        System.out.println("[p4-eval] 开始：游标写入 + 跨会话摘要回忆");
    }

    @AfterClass
    public static void done() {
        System.out.println("[p4-eval] 结束");
    }

    @Test
    public void cursorUpdateShouldRejectStaleWriter() {
        cleanupRows();
        ChatSessionSummaryEntity first = new ChatSessionSummaryEntity();
        first.setSessionId(SESSION_A);
        first.setUserId(USER_ID);
        first.setSummary("第一版摘要");
        first.setCoveredUntilId(10L);
        first.setIncrements(1);
        assertTrue("首次插入应成功", summaryRepository.insertIfAbsent(first));
        assertFalse("重复插入应返回 false（主键冲突翻译）", summaryRepository.insertIfAbsent(first));

        ChatSessionSummaryEntity second = new ChatSessionSummaryEntity();
        second.setSessionId(SESSION_A);
        second.setUserId(USER_ID);
        second.setSummary("第二版摘要");
        second.setCoveredUntilId(20L);
        second.setIncrements(2);
        assertTrue("游标匹配时更新成功", summaryRepository.updateIfCursor(second, 10L));
        assertFalse("拿旧游标再写应失败（并发保护）", summaryRepository.updateIfCursor(first, 10L));

        ChatSessionSummaryEntity stored = summaryRepository.findBySessionId(SESSION_A);
        assertNotNull(stored);
        assertEquals("第二次写入的内容必须保留", "第二版摘要", stored.getSummary());
        assertEquals(20L, stored.getCoveredUntilId().longValue());
        assertEquals(2, stored.getIncrements().intValue());

        List<ChatSessionSummaryEntity> recent =
                summaryRepository.findRecentByUser(USER_ID, "p4-s-other", 5);
        assertTrue("按用户能查到（跨会话回忆的数据源）", recent.stream()
                .anyMatch(item -> SESSION_A.equals(item.getSessionId())));
        assertTrue("排除自身会话",
                summaryRepository.findRecentByUser(USER_ID, SESSION_A, 5).stream()
                        .noneMatch(item -> SESSION_A.equals(item.getSessionId())));
        cleanupRows();
    }

    @Test
    public void recallShouldBringSummaryFromAnotherSession() {
        cleanupRows();
        summaryVectorService.index(SESSION_A, USER_ID,
                "用户反馈 nginx 持续 502，排查结论是上游服务连接池打满，调大连接池并重启后恢复。");
        summaryVectorService.index(SESSION_B, USER_ID,
                "磁盘 inode 耗尽导致无法创建新文件，清理 /var/spool 下的旧邮件队列后恢复。");
        // 当前会话自己也索引一条，验证会被排除
        summaryVectorService.index(SESSION_CURRENT, USER_ID,
                "当前会话：用户正在询问 nginx 502 的问题。");

        Map<String, Object> ctx = sessionRecallProvider.provide(
                SESSION_CURRENT, USER_ID, null, "",
                List.of(userMessage("上次那台机器 nginx 一直 502 是怎么解决的？")));

        Object recall = ctx.get("sessionRecallSummary");
        assertNotNull("应召回跨会话摘要", recall);
        String text = String.valueOf(recall);
        System.out.println("[p4-eval] 召回内容：\n" + text);
        assertTrue("应命中连接池那条结论", text.contains("连接池"));
        assertFalse("当前会话自身必须被排除", text.contains("当前会话"));
        // 只有 3 条摘要、limit=2 时"另一条"必然被带进来；这里只要求"相关的那条排在第一条"，
        // 不要求无关摘要完全消失（rrf 分数量级太小，硬阈值会把召回本身掐死）。
        String first = text.split("\n")[0];
        assertTrue("最相关的那条必须排在第一位，实际：" + first, first.contains("连接池"));
        cleanupVectors();
        cleanupRows();
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> userMessage(String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", content);
        return message;
    }

    private void cleanupRows() {
        jdbcTemplate.update("DELETE FROM chat_session_summary WHERE user_id = ?", USER_ID);
    }

    private void cleanupVectors() {
        try {
            vectorStore.delete(summaryVectorService.collectionName(),
                    List.of("sum:" + SESSION_A, "sum:" + SESSION_B, "sum:" + SESSION_CURRENT));
        } catch (Exception e) {
            System.out.println("[p4-eval] 清理向量失败（可忽略）：" + e.getMessage());
        }
    }
}