package com.shiyi.test.knowledge;

import com.shiyi.domain.agent.service.knowledge.impl.KnowledgeIndexer;
import com.shiyi.domain.agent.service.knowledge.impl.KnowledgeSourceSync;
import jakarta.annotation.Resource;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * md 静态知识同步的手工验证用例（打真实 Qdrant + 真实 embedding，不参与 CI）。
 * <p>
 * 验证"md → 切分 → 向量化 → Qdrant（payload 带全文）"整条链路，断言三件事：
 * 切出 70 条、写入 70 条、重复同步不重复写入（幂等）。
 * <p>
 * 为什么是手工用例：app 模块 pom 里 {@code skipTests=true}，跑法见
 * {@code java -cp "target/test-classes;target/classes;$(cat D:/Temp/app-cp.txt)" org.junit.runner.JUnitCore com.shiyi.test.knowledge.KnowledgeMdSyncTest}。
 *
 * @author shiyi
 */
@RunWith(SpringRunner.class)
@SpringBootTest(properties = {
        "app.rag.enabled=true",
        "app.rag.vector.enabled=true",
        "app.rag.vector.store=qdrant",
        "app.rag.knowledge.sync-on-startup=false",
        "app.rag.knowledge.dir=../docs/knowledge-base"
})
public class KnowledgeMdSyncTest {

    @Resource
    private KnowledgeSourceSync knowledgeSourceSync;

    @Test
    public void shouldSyncMarkdownIntoQdrantIdempotently() {
        KnowledgeIndexer.SyncStats first = knowledgeSourceSync.sync();
        assertEquals("应切出 70 条", 70, first.sections());
        assertEquals("首次应写入 70 条", 70, first.indexed());

        KnowledgeIndexer.SyncStats second = knowledgeSourceSync.sync();
        assertEquals("重复同步仍写 70 条（覆盖写）", 70, second.indexed());
        assertEquals("重复同步不应清理任何条目", 0, second.deleted());
        assertTrue("同步耗时应可观测", first.elapsedMs() >= 0);
    }
}
