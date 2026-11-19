package com.shiyi.test.memory;

import com.shiyi.domain.agent.adapter.vector.VectorStore;
import com.shiyi.domain.agent.model.entity.LongTermMemoryEntity;
import com.shiyi.domain.agent.service.ILongTermMemoryService;
import com.shiyi.domain.agent.service.memory.MemoryVectorService;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/**
 * P2 语义召回评测：固定 10 条"记忆内容与查询用词不同"的用例。
 * <p>
 * 为什么要在 app 模块跑真栈：语义召回的质量取决于 embedding 模型与 Qdrant 的融合排序，
 * mock 只能验证编排、验证不了"同义不同词能不能召回"。所以这里连真 MySQL、真 Qdrant、
 * 真 DashScope embedding 一起跑。
 * <p>
 * 评测口径两段：
 * <ol>
 *   <li><b>候选段</b>：{@link MemoryVectorService#recallScores} 返回的候选里，正确记忆排在第几——
 *       这是 P2 新增能力本身的效果；</li>
 *   <li><b>端到端段</b>：{@link ILongTermMemoryService#queryRelevantMemories} 用六因子打分后再收窄到
 *       top5，正确记忆是否还在——验证"语义候选进来以后没被业务打分挡掉"。</li>
 * </ol>
 * 关键词路这里天然是 0 命中：每条记忆的 keywords 都刻意与查询用词不重叠，
 * 模拟真实场景里"用户换了说法就搜不到"的 badcase。
 * <p>
 * 数据隔离：固定 user_id = {@code sem-eval-user}，测试前后清理 MySQL 行与 Qdrant 点，
 * 不污染真实记忆。
 *
 * @see MemoryVectorService
 */
@RunWith(SpringRunner.class)
@SpringBootTest(properties = {
        "app.rag.enabled=true",
        "app.rag.vector.enabled=true",
        "app.rag.vector.store=qdrant",
        "app.rag.embedding.enabled=true",
        // 索引由本测试显式驱动，关掉定时任务避免和断言抢时序
        "app.rag.memory-index.enabled=false"
})
public class MemorySemanticRecallTest {

    private static final String USER_ID = "sem-eval-user";
    private static final String HOST_KEY = "sem-eval-host";
    private static final int TOP_N = 5;

    /**
     * 十条用例：{记忆内容, 记忆关键词, 用户查询, 期望命中的用例下标}。
     * <p>
     * 查询刻意避开内容里的原词——比如记忆里有"OOM"，查询说"莫名退出"；
     * 记忆里有"主从延迟"，查询说"读到老数据"。字面匹配全 miss，靠语义才对得上。
     */
    private static final String[][] CASES = {
            {"订单服务每隔几分钟就被系统杀掉重启，dmesg 里能看到 oom-killer 的记录",
                    "oom-killer,内存", "Java 进程总是莫名退出，内核日志里有被杀的痕迹", "0"},
            {"nginx 报 502 Bad Gateway，原因是上游 tomcat 的连接池被打满",
                    "502,连接池", "网关一直返回 502，后端好像撑不住并发", "1"},
            {"MySQL 主从复制延迟到 20 分钟，从库读到了旧数据",
                    "主从,延迟", "数据库读到的是老数据，主备之间不同步", "2"},
            {"磁盘 inode 用完了，df -i 显示 100%，新建文件报 No space left on device",
                    "inode,磁盘", "新建文件提示空间不足，但 df 看容量还有富余", "3"},
            {"HTTPS 证书过期，导致小程序所有请求握手失败",
                    "证书,过期", "接口全部连不上，提示证书已经不在有效期内", "4"},
            {"SSH 登录很慢，根因是 DNS 反向解析超时",
                    "ssh,登录慢", "连服务器要等十几秒才出现密码提示", "5"},
            {"误改 sudoers 文件导致全员无法提权，sudo 直接报语法错误",
                    "sudoers,提权", "执行 sudo 就报配置语法错误，谁都提不了权", "6"},
            {"容器启动失败，退出码 137，说明被 OOM kill 掉了",
                    "容器,退出码", "k8s pod 反复重启，状态码 137", "7"},
            {"Redis 内存打满触发 maxmemory 淘汰，缓存命中率骤降",
                    "redis,内存", "缓存突然不好用了，大量请求直接打到数据库", "8"},
            {"crontab 任务没执行，因为环境变量 PATH 不完整，脚本找不到命令",
                    "crontab,PATH", "定时脚本手动能跑，到了点就是不执行", "9"}
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private MemoryVectorService memoryVectorService;

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    @Resource
    private VectorStore vectorStore;

    /** 用例下标 → 记忆主键，断言时用下标定位，避免依赖自增 id 的具体值。 */
    private static final List<Long> MEMORY_IDS = new ArrayList<>();

    @BeforeClass
    public static void banner() {
        System.out.println("[memory-semantic] 开始：10 条同义不同词用例，先清历史数据");
    }

    @AfterClass
    public static void cleanup() {
        // 清理放在实例方法之外的静态上下文不方便拿 Bean，改由 test 末尾调用 cleanupData()
        System.out.println("[memory-semantic] 结束");
    }

    @Test
    public void semanticRecallShouldHitAtLeastEightOfTen() {
        cleanupData();
        seedMemories();
        indexAll();

        int candidateHits = 0;
        int endToEndHits = 0;
        List<String> details = new ArrayList<>();
        for (int i = 0; i < CASES.length; i++) {
            String query = CASES[i][2];
            Long expectedId = MEMORY_IDS.get(i);

            Map<Long, Double> scores = memoryVectorService.recallScores(USER_ID, HOST_KEY, query, 50);
            int rank = rankOf(scores, expectedId);
            boolean candidateHit = rank >= 0 && rank < TOP_N;
            List<LongTermMemoryEntity> recalled = longTermMemoryService.queryRelevantMemories(USER_ID, HOST_KEY, query, TOP_N);
            boolean endToEndHit = recalled.stream().anyMatch(memory -> expectedId.equals(memory.getId()));
            if (candidateHit) {
                candidateHits++;
            }
            if (endToEndHit) {
                endToEndHits++;
            }
            details.add(String.format("case%d rank=%d candidate=%s e2e=%s query=%s",
                    i + 1, rank, candidateHit, endToEndHit, query));
        }
        System.out.println("[memory-semantic] candidateHits=" + candidateHits + "/" + CASES.length
                + " endToEndHits=" + endToEndHits + "/" + CASES.length);
        details.forEach(line -> System.out.println("[memory-semantic]   " + line));

        cleanupData();
        assertTrue("语义候选命中应 ≥8/10，实际 " + candidateHits + "/" + CASES.length,
                candidateHits >= 8);
        assertTrue("端到端召回命中应 ≥8/10，实际 " + endToEndHits + "/" + CASES.length,
                endToEndHits >= 8);
    }

    // ==================== 数据准备与清理 ====================

    private void cleanupData() {
        jdbcTemplate.update("DELETE FROM long_term_memory WHERE user_id = ?", USER_ID);
        if (!MEMORY_IDS.isEmpty()) {
            List<String> pointIds = new ArrayList<>(MEMORY_IDS.size());
            for (Long id : MEMORY_IDS) {
                pointIds.add("mem:" + id);
            }
            try {
                vectorStore.delete(memoryVectorService.collectionName(), pointIds);
            } catch (Exception e) {
                System.out.println("[memory-semantic] 清理向量点失败（可忽略）：" + e.getMessage());
            }
            MEMORY_IDS.clear();
        }
    }

    private void seedMemories() {
        for (int i = 0; i < CASES.length; i++) {
            String content = CASES[i][0];
            String keywords = CASES[i][1];
            jdbcTemplate.update("INSERT INTO long_term_memory "
                            + "(user_id, `scope`, host_key, session_id, memory_type, memory_key, content, keywords,"
                            + " source_role, confidence, importance, hit_count, embedding_status, embedding_attempts)"
                            + " VALUES (?, 'HOST', ?, 'sem-session', 'TROUBLESHOOTING_CASE', ?, ?, ?,"
                            + " 'assistant', 0.90, 0.70, 1, 0, 0)",
                    USER_ID, HOST_KEY, "sem-key-" + i, content, keywords);
            Long id = jdbcTemplate.queryForObject(
                    "SELECT id FROM long_term_memory WHERE user_id = ? AND memory_key = ?", Long.class,
                    USER_ID, "sem-key-" + i);
            MEMORY_IDS.add(id);
        }
        System.out.println("[memory-semantic] seeded=" + MEMORY_IDS.size());
    }

    private void indexAll() {
        int total = 0;
        for (int round = 0; round < 5; round++) {
            int indexed = memoryVectorService.indexPending(0);
            total += indexed;
            if (indexed == 0) {
                break;
            }
        }
        System.out.println("[memory-semantic] indexed=" + total + " collection=" + memoryVectorService.collectionName());
        assertTrue("记忆索引应全部成功，实际 " + total + "/" + CASES.length, total >= CASES.length);
    }

    private static int rankOf(Map<Long, Double> scores, Long expectedId) {
        int rank = 0;
        for (Map.Entry<Long, Double> entry : new LinkedHashMap<>(scores).entrySet()) {
            if (entry.getKey().equals(expectedId)) {
                return rank;
            }
            rank++;
        }
        return -1;
    }
}