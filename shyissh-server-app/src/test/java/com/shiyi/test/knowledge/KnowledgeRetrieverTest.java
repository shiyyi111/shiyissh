package com.shiyi.test.knowledge;

import com.shiyi.domain.agent.service.knowledge.KnowledgeRetriever;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;

import static org.junit.Assert.assertNotNull;

/**
 * 知识检索的端到端手工验证（关键词 + 向量 + RRF 融合）。
 * <p>
 * 挑的几条查询覆盖四种典型输入：口语化（磁盘满了）、带强信号（nginx 502）、
 * 同义词（内存爆了）、以及一条几乎不会命中的无意义串（验证空结果不会抛异常）。
 *
 * @author shiyi
 */
@RunWith(SpringRunner.class)
@SpringBootTest(properties = {
        "app.rag.enabled=true",
        "app.rag.vector.enabled=true",
        "app.rag.rewrite.enabled=true"
})
public class KnowledgeRetrieverTest {

    @Resource
    private KnowledgeRetriever knowledgeRetriever;

    @Test
    public void shouldRetrieveRelevantKnowledge() {
        String[] queries = {
                "磁盘满了怎么排查",
                "nginx 502 是什么原因",
                "内存爆了进程被杀了",
                "网站打不开",
                "zzz 完全不相关的内容 qqq"
        };
        for (String query : queries) {
            KnowledgeRetriever.Result result = knowledgeRetriever.retrieve("default", "", query, 5);
            assertNotNull("检索结果不应为 null： " + query, result);
            System.out.println("[TEST] 查询=" + query
                    + " | 归一化=" + result.normalizedQuery()
                    + " | 改写=" + result.rewritten()
                    + " | 耗时=" + result.elapsedMs() + "ms"
                    + " | 命中=" + result.hits().size());
            for (KnowledgeRetriever.Hit hit : result.hits()) {
                System.out.println("[TEST]    " + hit.docId() + " [" + hit.kind() + "] "
                        + hit.title() + " score=" + String.format("%.4f", hit.score())
                        + " src=" + hit.source());
            }
        }
    }
}