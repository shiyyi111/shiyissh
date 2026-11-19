package com.shiyi.test.knowledge;

import com.shiyi.domain.agent.graph.support.GraphMemoryToolService;
import com.shiyi.domain.agent.graph.support.WikiSearchRequest;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 验证模型侧工具 {@code wikiSearch} 拿到的确实是混合检索结果。
 * <p>
 * 为什么单独测这一层：检索器本身通了，不代表工具链通 —— 工具是模型唯一能触达知识库的入口，
 * 它返回的文本格式直接决定模型能不能引用（编号、来源、命令）。这里断言的是"模型看到的东西"。
 *
 * @author shiyi
 */
@RunWith(SpringRunner.class)
@SpringBootTest(properties = {
        "app.rag.enabled=true",
        "app.rag.vector.enabled=true"
})
public class KnowledgeToolTest {

    @Resource
    private GraphMemoryToolService memoryToolService;

    @Test
    public void shouldReturnKnowledgeForRelevantQuery() {
        String result = memoryToolService.wikiSearch("tester", "", new WikiSearchRequest("磁盘满了怎么排查", 3));
        System.out.println("[TEST] 相关查询返回：\n" + result);
        assertTrue("应返回知识库检索结果", result.contains("知识库检索结果"));
        assertTrue("应命中磁盘相关内容", result.contains("磁盘"));
        assertTrue("应带引用编号", result.contains("[1]"));
    }

    @Test
    public void shouldReturnEmptyHintForIrrelevantQuery() {
        String result = memoryToolService.wikiSearch("tester", "", new WikiSearchRequest("zzz 完全不相关的内容 qqq", 3));
        System.out.println("[TEST] 无关查询返回：" + result);
        assertFalse("无关查询不应返回条目", result.contains("[1]"));
        assertTrue("应提示没有找到", result.contains("没有找到"));
    }
}