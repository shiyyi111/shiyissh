package com.shiyi.test.checkpoint;

import com.shiyi.domain.agent.service.chat.GraphChatService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 检查点端到端人工验证（手动运行，不参与常规回归）。
 * <p>
 * 验证的是"跑一次会触发审批的请求 → 图在 [approval] 前中断 → 状态真的落进 MySQL"，
 * 这是 {@code MemorySaver} 换 {@code MysqlSaver} 唯一值得单独验一次的地方：
 * 单测层面已经把"建表"和"未知线程返回空"覆盖了，但"中断时有没有写库"只有真跑一遍才知道。
 * <p>
 * 运行方式见类的注释下方命令；默认 {@code @Ignore} 掉，因为它会花真模型的钱、且依赖模型是否愿意发高风险命令。
 *
 * @author shiyi
 */
@RunWith(SpringRunner.class)
@SpringBootTest
public class CheckpointE2EManualTest {

    @Resource
    private GraphChatService graphChatService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Test
    public void shouldPersistCheckpointWhenInterruptedForApproval() {
        String sessionId = "cp-e2e-" + System.currentTimeMillis();
        List<String> events = graphChatService
                .handleMessageStream("e2e-user", sessionId,
                        "这是我自己建的临时测试目录，请直接执行：rm -rf /tmp/shiyissh-e2e-dirtest", null, null)
                .collectList()
                .block();

        String done = events == null ? "" : events.stream()
                .filter(e -> e.contains("\"type\":\"done\""))
                .findFirst().orElse("");
        System.out.println("[TEST] sessionId=" + sessionId);
        System.out.println("[TEST] done 事件：" + done);

        Map<String, Object> checkpoint = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS rows_total FROM graph_checkpoint");
        System.out.println("[TEST] graph_checkpoint 行数=" + checkpoint.get("rows_total"));

        Map<String, Object> threads = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS rows_total FROM graph_thread");
        System.out.println("[TEST] graph_thread 行数=" + threads.get("rows_total"));
    }
}