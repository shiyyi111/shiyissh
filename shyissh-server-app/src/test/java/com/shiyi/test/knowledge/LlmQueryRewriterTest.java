package com.shiyi.test.knowledge;

import com.shiyi.domain.agent.service.knowledge.LlmQueryRewriter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;

/**
 * 查询改写的手工验证：主要看两件事——模型能不能把口语化问题改写成检索友好的说法、耗时是否可接受。
 * <p>
 * 耗时要单看：它只在首轮召回不足时触发，但 1.5 秒超时的阈值不是拍脑袋定的，得拿真实延迟校准。
 *
 * @author shiyi
 */
@RunWith(SpringRunner.class)
@SpringBootTest(properties = {
        "app.rag.enabled=true",
        "app.rag.rewrite.enabled=true",
        "app.rag.rewrite.timeout-ms=8000"
})
public class LlmQueryRewriterTest {

    @Resource
    private LlmQueryRewriter llmQueryRewriter;

    @Test
    public void shouldRewriteColloquialQuery() {
        String[] queries = {"服务器卡得动不了", "内存爆了进程被杀了", "网站打不开"};
        for (String query : queries) {
            long startedAt = System.currentTimeMillis();
            String rewritten = llmQueryRewriter.rewrite(query);
            long elapsed = System.currentTimeMillis() - startedAt;
            System.out.println("[TEST] 改写 \"" + query + "\" → \"" + rewritten + "\" 耗时=" + elapsed + "ms");
        }
    }
}