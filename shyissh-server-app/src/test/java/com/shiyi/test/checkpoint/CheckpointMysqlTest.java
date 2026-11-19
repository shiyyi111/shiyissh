package com.shiyi.test.checkpoint;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * 检查点存储装配验证：确认默认用的是 MySQL 实现、表已建好、能按线程查询。
 * <p>
 * 这个测试的价值在于"启动即验证"——{@link MysqlSaver} 在构造时建表，
 * 如果数据源权限不足或表结构冲突，要么启动失败，要么回退内存实现；
 * 两种情况都会让 {@code instanceOf MysqlSaver} 断言失败，从而及早暴露，
 * 而不是等到线上有人点"批准"时才发现断点根本存不下来。
 *
 * @author shiyi
 */
@RunWith(SpringRunner.class)
@SpringBootTest
public class CheckpointMysqlTest {

    @Resource
    private BaseCheckpointSaver checkpointSaver;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Test
    public void shouldUseMysqlSaverAndCreateTables() {
        assertTrue("默认应装配 MysqlSaver，实际：" + checkpointSaver.getClass().getName(),
                checkpointSaver instanceof MysqlSaver);

        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name LIKE 'GRAPH_%'", String.class);
        System.out.println("[TEST] 检查点表：" + tables);
        // MySQL 在 Windows 上默认 lowercase_table_names=1，表名会是小写，这里只比较名字不比较大小写。
        assertTrue("应存在 GRAPH_THREAD 表，实际：" + tables,
                tables.stream().anyMatch(name -> "GRAPH_THREAD".equalsIgnoreCase(name)));
        assertTrue("应存在 GRAPH_CHECKPOINT 表，实际：" + tables,
                tables.stream().anyMatch(name -> "GRAPH_CHECKPOINT".equalsIgnoreCase(name)));
    }

    @Test
    public void shouldReturnEmptyForUnknownThread() {
        RunnableConfig config = RunnableConfig.builder().threadId("test-non-existent-thread").build();
        assertTrue("未知线程不应有检查点", checkpointSaver.get(config).isEmpty());
        assertTrue("未知线程不应有检查点列表", checkpointSaver.list(config).isEmpty());
    }
}