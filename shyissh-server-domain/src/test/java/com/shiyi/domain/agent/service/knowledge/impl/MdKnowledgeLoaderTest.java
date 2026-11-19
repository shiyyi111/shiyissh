package com.shiyi.domain.agent.service.knowledge.impl;

import org.junit.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * md 切分器测试：直接读仓库里真实的 {@code docs/knowledge-base}，断言"切出来的条目数与编号"。
 * <p>
 * 为什么用真实文件而不是临时目录：这份 md 就是生产索引的输入，格式一旦被改坏
 * （标题降级成三级、编号改了前缀、命令块缩进变了），这里要能第一时间红。
 * 临时目录只能证明"解析器按我以为的格式工作"，证明不了"仓库里的文件符合预期"。
 *
 * @author shiyi
 */
public class MdKnowledgeLoaderTest {

    private static Path knowledgeDir() {
        return Path.of(System.getProperty("user.dir")).resolve("../docs/knowledge-base").normalize();
    }

    @Test
    public void shouldSplitRepositoryMarkdownIntoNumberedSections() {
        List<MdKnowledgeLoader.Section> sections = MdKnowledgeLoader.loadAll(knowledgeDir());

        assertEquals("runbook 40 + policy 20 + incident 10 = 70 条", 70, sections.size());
        Map<String, MdKnowledgeLoader.Section> byId = sections.stream()
                .collect(Collectors.toMap(MdKnowledgeLoader.Section::docId, s -> s, (a, b) -> a));
        assertTrue("KB-001 必须在", byId.containsKey("KB-001"));
        assertTrue("P-001 必须在", byId.containsKey("P-001"));
        assertTrue("INC-001 必须在", byId.containsKey("INC-001"));
        assertEquals("KB- 前缀应识别为 runbook", "runbook", byId.get("KB-001").kind());
        assertEquals("P- 前缀应识别为 policy", "policy", byId.get("P-001").kind());
        assertEquals("INC- 前缀应识别为 incident", "incident", byId.get("INC-001").kind());
    }

    @Test
    public void sectionShouldCarryFullBodyAndStructuredFields() {
        MdKnowledgeLoader.Section disk = MdKnowledgeLoader.loadAll(knowledgeDir()).stream()
                .filter(s -> "KB-001".equals(s.docId()))
                .findFirst()
                .orElseThrow();

        assertFalse("标题不应为空", disk.title().isBlank());
        assertTrue("正文应包含处理步骤", disk.content().contains("处理"));
        assertTrue("正文应包含命令块", disk.content().contains("命令"));
        assertFalse("命令字段应被单独抽出来", disk.commands().isBlank());
        assertFalse("标签字段应被单独抽出来", disk.tags().isBlank());
        assertEquals("内容指纹应为 40 位 sha1", 40, disk.contentHash().length());
        assertEquals("runbook.md", disk.path());
    }
}
