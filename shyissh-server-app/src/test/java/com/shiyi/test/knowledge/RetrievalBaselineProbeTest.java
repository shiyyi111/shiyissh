package com.shiyi.test.knowledge;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.shiyi.domain.agent.service.knowledge.KnowledgeRetriever;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 检索基线探针（M0）：跑固定查询集，输出 recall@5 / MRR@5 / p95 耗时。
 * <p>
 * 为什么用集成测试而不是脚本：要复用真实 Bean（归一化、同义词表、关键词 SQL、embedding、Qdrant 客户端、RRF），
 * 任何一处换实现都会在同一个探针里体现出来，重构前后才能对比。
 * <p>
 * 查询集分四组，覆盖真实输入的差异：
 * <ul>
 *   <li>exact：带强信号（错误串、命令、端口），关键词路应该赢</li>
 *   <li>colloquial：用户口语（"磁盘满了""内存爆了"），向量路应该赢</li>
 *   <li>policy / incident：跨 kind 的语义匹配，检验是否被 runbook 挤掉</li>
 * </ul>
 * LLM 改写默认关闭（{@code app.rag.rewrite.enabled=false}）；要做"改写 vs 不改写"的对照，
 * 传入 {@code -Dapp.rag.rewrite.enabled=true -Dapp.rag.rewrite.trigger-min-hits=6} 即可，
 * 不要再在注解里写死该属性（注解里的 properties 优先级高于 -D，会盖掉命令行）。
 *
 * @author shiyi
 */
@RunWith(SpringRunner.class)
@SpringBootTest(properties = {
        "app.rag.enabled=true",
        "app.rag.vector.enabled=true",
        "app.rag.vector.store=qdrant"
})
public class RetrievalBaselineProbeTest {

    /** 评测输出目录；不存在时创建。可通过 -Dbaseline.out 覆盖。 */
    private static final String OUT_DIR = System.getProperty("baseline.out", "D:/Agent/eval");

    private static final int TOP_K = 5;

    @Resource
    private KnowledgeRetriever knowledgeRetriever;

    /** 查询集：{query, group, 期望命中的 docId...}。允许多个 gold（同一问题的不同权威条目）。 */
    private static final String[][] CASES = {
            {"磁盘满了怎么排查", "colloquial", "KB-001"},
            {"No space left on device", "exact", "KB-001"},
            {"inode 用完了怎么办", "colloquial", "KB-002"},
            {"内存爆了进程被杀了", "colloquial", "KB-003", "KB-007"},
            {"swap 占用很高", "exact", "KB-004"},
            {"load average 很高但 CPU 很闲", "exact", "KB-006"},
            {"进程莫名其妙消失", "colloquial", "KB-007"},
            {"启动服务提示 Address already in use", "exact", "KB-008"},
            {"nginx 起不来 systemctl 报 failed", "colloquial", "KB-009"},
            {"nginx 502 是什么原因", "colloquial", "KB-010", "INC-003"},
            {"网关超时 504", "colloquial", "KB-011"},
            {"TIME_WAIT 特别多", "exact", "KB-013", "INC-008"},
            {"域名解析不了 UnknownHostException", "exact", "KB-014"},
            {"ping 不通 端口连不上", "colloquial", "KB-015"},
            {"防火墙挡了访问", "colloquial", "KB-016"},
            {"ssh 登录特别慢", "colloquial", "KB-017"},
            {"ssh 免密登录被拒绝", "colloquial", "KB-018"},
            {"Too many open files", "exact", "KB-019"},
            {"服务器时间不对", "colloquial", "KB-020"},
            {"crontab 定时任务没跑", "colloquial", "KB-021"},
            {"日志文件突然变得很大", "colloquial", "KB-022"},
            {"MySQL 报 Too many connections", "exact", "KB-023"},
            {"数据库查询变慢", "colloquial", "KB-024"},
            {"主从延迟越来越大", "colloquial", "KB-025"},
            {"redis 内存满了", "colloquial", "KB-026"},
            {"java 进程 OOM", "exact", "KB-027"},
            {"容器一直重启退出码 137", "exact", "KB-028"},
            {"证书过期了", "colloquial", "KB-029"},
            {"生产变更前要做什么", "policy", "P-001"},
            {"能不能直接删日志文件", "policy", "P-003"},
            {"定时任务并发导致重复扣款", "incident", "INC-007"},
            {"nginx 502 上游连接池耗尽", "incident", "INC-003", "KB-010"}
    };

    @Test
    public void shouldReportBaselineMetrics() throws Exception {
        List<double[]> latencies = new ArrayList<>();
        JSONArray records = new JSONArray();
        int hitAt5 = 0;
        double mrrSum = 0;
        int emptyCount = 0;
        int hitAt1 = 0;
        int hitAt3 = 0;

        for (String[] c : CASES) {
            String query = c[0];
            String group = c[1];
            Set<String> golds = new LinkedHashSet<>(Arrays.asList(c).subList(2, c.length));
            KnowledgeRetriever.Result result = knowledgeRetriever.retrieve("default", "", query, TOP_K);
            latencies.add(new double[]{result.elapsedMs()});

            List<String> rawDocIds = new ArrayList<>();
            for (KnowledgeRetriever.Hit hit : result.hits()) {
                rawDocIds.add(hit.docId());
            }
            // P0-1 已修：hybrid 检索返回的就是业务编号（doc_id，如 KB-001），不再需要主键翻译。
            List<String> docIds = new ArrayList<>(rawDocIds);
            int rank = -1;
            for (int i = 0; i < docIds.size(); i++) {
                if (golds.contains(docIds.get(i))) {
                    rank = i + 1;
                    break;
                }
            }
            if (rank > 0) {
                hitAt5++;
                mrrSum += 1.0 / rank;
                if (rank == 1) {
                    hitAt1++;
                }
                if (rank <= 3) {
                    hitAt3++;
                }
            }
            if (result.hits().isEmpty()) {
                emptyCount++;
            }
            System.out.println("[BASELINE] " + group + " q=" + query + " gold=" + golds
                    + " top=" + docIds + " rank=" + rank + " " + result.elapsedMs() + "ms");

            JSONObject record = new JSONObject();
            record.put("query", query);
            record.put("group", group);
            record.put("golds", golds);
            record.put("docIds", docIds);
            record.put("rawDocIds", rawDocIds);
            record.put("rank", rank);
            record.put("hits", hitDetail(result));
            record.put("elapsedMs", result.elapsedMs());
            record.put("normalizedQuery", result.normalizedQuery());
            record.put("rewritten", result.rewritten());
            records.add(record);
        }

        int n = CASES.length;
        double recall = (double) hitAt5 / n;
        double mrr = mrrSum / n;
        double[] sorted = latencies.stream().mapToDouble(a -> a[0]).sorted().toArray();
        double avg = Arrays.stream(sorted).average().orElse(0);
        double p95 = sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.95) - 1)];

        System.out.println(String.format(Locale.ROOT,
                "[BASELINE] SUMMARY n=%d hit@1=%.4f hit@3=%.4f recall@5=%.4f MRR@5=%.4f avg=%.1fms p95=%.1fms empty=%d",
                n, (double) hitAt1 / n, (double) hitAt3 / n, recall, mrr, avg, p95, emptyCount));

        JSONObject summary = new JSONObject();
        summary.put("timestamp", java.time.OffsetDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).toString());
        summary.put("queries", n);
        summary.put("topK", TOP_K);
        String stage = System.getProperty("probe.stage", "unspecified");
        summary.put("stage", stage);
        summary.put("hitAt1", round((double) hitAt1 / n));
        summary.put("hitAt3", round((double) hitAt3 / n));
        summary.put("recallAt5", round(recall));
        summary.put("mrrAt5", round(mrr));
        summary.put("avgMs", round(avg));
        summary.put("p95Ms", round(p95));
        summary.put("emptyResults", emptyCount);
        summary.put("rewriteEnabled", false);
        summary.put("docIdFormat", "命中 docId 即业务编号（KB-001/P-001/INC-001）；P0-1 已修，无需主键翻译");
        summary.put("notes", "M0 基线：关键词 + 向量 + RRF 现状，LLM 改写关闭以保证可重复");
        summary.put("records", records);

        File dir = new File(OUT_DIR);
        Files.createDirectories(dir.toPath());
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        File json = new File(dir, "baseline-" + stamp + ".json");
        File latest = new File(dir, "baseline.json");
        String text = JSON.toJSONString(summary, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
        Files.writeString(json.toPath(), text, StandardCharsets.UTF_8);
        Files.writeString(latest.toPath(), text, StandardCharsets.UTF_8);
        System.out.println("[BASELINE] 已写入 " + json.getAbsolutePath() + " 与 " + latest.getAbsolutePath());
        appendLedger(dir, stage, summary);

        org.junit.Assert.assertEquals("用例数应与查询集一致", n, records.size());
    }

    private static void appendLedger(File dir, String stage, JSONObject summary) throws Exception {
        JSONObject line = new JSONObject();
        line.put("stage", stage);
        line.put("time", summary.get("timestamp"));
        line.put("n", summary.get("queries"));
        line.put("hitAt1", summary.get("hitAt1"));
        line.put("hitAt3", summary.get("hitAt3"));
        line.put("recallAt5", summary.get("recallAt5"));
        line.put("mrrAt5", summary.get("mrrAt5"));
        line.put("avgMs", summary.get("avgMs"));
        line.put("p95Ms", summary.get("p95Ms"));
        line.put("empty", summary.get("emptyResults"));
        File ledger = new File(dir, "ledger.jsonl");
        Files.writeString(ledger.toPath(), JSON.toJSONString(line) + System.lineSeparator(),
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        System.out.println("[BASELINE] ledger += " + line);
    }

    private static JSONArray hitDetail(KnowledgeRetriever.Result result) {
        JSONArray hits = new JSONArray();
        for (KnowledgeRetriever.Hit hit : result.hits()) {
            JSONObject h = new JSONObject();
            h.put("docId", hit.docId());
            h.put("title", hit.title());
            h.put("kind", hit.kind());
            h.put("score", round(hit.score()));
            hits.add(h);
        }
        return hits;
    }

    private static double round(double v) {
        return Math.round(v * 10000d) / 10000d;
    }
}