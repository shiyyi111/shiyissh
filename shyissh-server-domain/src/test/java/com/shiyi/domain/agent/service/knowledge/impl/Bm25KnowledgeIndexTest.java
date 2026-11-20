package com.shiyi.domain.agent.service.knowledge.impl;

import com.shiyi.domain.agent.model.entity.WikiEntryEntity;
import com.shiyi.domain.agent.model.valobj.properties.RagProperties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 关键词腿（Lucene + 中文分词 + BM25）的回归测试。
 * <p>
 * 换掉 2-gram 稀疏向量之后最容易悄悄坏的三件事：
 * <ol>
 *   <li>中文查询召回不了中文正文（分析链在索引与查询两侧不一致）；</li>
 *   <li>ASCII 命令串被中文分词器切碎，{@code nginx}/{@code df -h} 这类精确串反而查不到；</li>
 *   <li>md 全量重建后旧文档还在索引里，差集清理等于白做。</li>
 * </ol>
 * 领域模块测试类路径没有 spring-test，{@code RagProperties} 用反射注入。
 *
 * @author shiyi
 */
public class Bm25KnowledgeIndexTest {

    private Bm25KnowledgeIndex index;

    private RagProperties ragProperties;

    @Before
    public void setUp() {
        ragProperties = new RagProperties();
        index = new Bm25KnowledgeIndex();
        setField(index, "ragProperties", ragProperties);
        index.init();
    }

    @After
    public void tearDown() {
        index.destroy();
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("注入失败：" + name, e);
        }
    }

    private static MdKnowledgeLoader.Section section(String docId, String kind, String title,
                                                     String content, String commands, String tags) {
        return new MdKnowledgeLoader.Section(docId, kind, title, content, commands, tags,
                "medium", "docs/knowledge-base/runbook.md", "hash-" + docId);
    }

    @Test
    public void chineseQueryShouldHitChineseSection() {
        index.rebuildMd(List.of(section("KB-001", "runbook", "磁盘空间不足",
                "先看 df -h 确认磁盘占用，再清理 /var/log 下的历史日志。", "df -h", "磁盘,日志")));

        List<Bm25KnowledgeIndex.Doc> hits = index.search("磁盘满了怎么办", 5);

        assertFalse("中文查询必须能命中中文正文", hits.isEmpty());
        assertEquals("KB-001", hits.get(0).docId());
        assertTrue("分数必须来自 BM25 而不是固定值", hits.get(0).score() > 0d);
    }

    @Test
    public void asciiCommandShouldStaySearchableAfterChineseAnalysis() {
        index.rebuildMd(List.of(section("KB-002", "runbook", "nginx 重启",
                "改完配置执行 systemctl restart nginx，再确认端口监听。", "systemctl restart nginx", "nginx,重启")));

        List<Bm25KnowledgeIndex.Doc> commandHits = index.search("nginx", 5);
        List<Bm25KnowledgeIndex.Doc> dashHits = index.search("df -h", 5);

        assertEquals("ASCII 服务名不能被中文分词切丢", "KB-002", commandHits.get(0).docId());
        // "df -h" 在这条文档的 commands 里不存在，但查询本身必须能被分析出整串而不是报错
        assertTrue("不存在的命令串返回空，而不是抛异常", dashHits.isEmpty());
    }

    @Test
    public void commandBlockShouldAlsoBeMatchedAsWholeString() {
        index.rebuildMd(List.of(section("KB-003", "runbook", "磁盘占用排查",
                "先执行 df -h 看整体占用。", "df -h", "磁盘")));

        List<Bm25KnowledgeIndex.Doc> hits = index.search("df -h", 5);

        assertFalse("命令块要能被整串命中（空白分词保住了 -h）", hits.isEmpty());
        assertEquals("KB-003", hits.get(0).docId());
    }

    @Test
    public void rebuildShouldDropStaleSections() {
        index.rebuildMd(List.of(
                section("KB-001", "runbook", "磁盘空间不足", "df -h 排查磁盘", "df -h", "磁盘"),
                section("KB-002", "policy", "变更窗口", "变更必须在窗口内进行", "", "变更")));
        assertEquals(2, index.search("磁盘 变更", 10).size());

        index.rebuildMd(List.of(section("KB-002", "policy", "变更窗口", "变更必须在窗口内进行", "", "变更")));

        List<Bm25KnowledgeIndex.Doc> hits = index.search("磁盘", 10);
        assertTrue("全量重建后旧章节必须消失", hits.stream().noneMatch(d -> "KB-001".equals(d.docId())));
        assertEquals("剩下的章节仍在", "KB-002", index.search("变更", 10).get(0).docId());
    }

    @Test
    public void wikiUpsertShouldKeepWikiPrefixAndNotDropMd() {
        index.rebuildMd(List.of(section("KB-001", "runbook", "磁盘空间不足", "df -h 排查磁盘", "df -h", "磁盘")));
        WikiEntryEntity wiki = new WikiEntryEntity();
        wiki.setId(7L);
        wiki.setTitle("生产库磁盘告警处置");
        wiki.setContent("磁盘满了先看 df -h，再清理 /var/log。");
        wiki.setTags("磁盘,告警");
        wiki.setSource("agent");

        index.upsertWiki(List.of(wiki));

        List<Bm25KnowledgeIndex.Doc> hits = index.search("磁盘", 10);
        assertTrue("md 与 wiki 应当同时可召回", hits.size() >= 2);
        assertTrue("wiki 命中要带 wiki: 前缀，便于检索器分流回表",
                hits.stream().anyMatch(d -> "wiki:7".equals(d.docId())));
        assertTrue("md 命中不能被 wiki upsert 覆盖掉",
                hits.stream().anyMatch(d -> "KB-001".equals(d.docId())));
    }

    @Test
    public void availableShouldBeFalseBeforeAnyDocument() {
        assertFalse("空索引必须显式不可用，让检索器退回稀疏路", index.available());
    }

    @Test
    public void disabledConfigShouldMakeIndexUnavailable() {
        index.rebuildMd(List.of(section("KB-001", "runbook", "磁盘空间不足", "df -h 排查磁盘", "df -h", "磁盘")));
        ragProperties.getKeyword().setEnabled(false);

        assertFalse("配置关掉后必须整体停用", index.available());
        assertTrue("停用后检索返回空，而不是半残结果", index.search("磁盘", 5).isEmpty());
    }

    /**
     * 用仓库里真实的 {@code docs/knowledge-base} 跑一遍：单测只校验"能切、能建、能查"，
     * 不校验排序质量（排序质量由 32 条评测集对比，需要模型外呼）。
     */
    @Test
    public void realKnowledgeBaseShouldBeLoadedAndSearchable() {
        java.nio.file.Path dir = java.nio.file.Path.of("../docs/knowledge-base");
        org.junit.Assume.assumeTrue("仓库知识库目录不存在则跳过", java.nio.file.Files.isDirectory(dir));
        List<MdKnowledgeLoader.Section> sections = MdKnowledgeLoader.loadAll(dir);
        org.junit.Assume.assumeFalse("知识库为空则跳过", sections.isEmpty());

        index.rebuildMd(sections);

        assertTrue("真实语料必须建出文档（应为 70 段左右）", index.available());
        assertFalse("中文运维查询要能命中", index.search("磁盘空间不足怎么排查", 5).isEmpty());
        assertFalse("服务名 + 状态码要能命中", index.search("nginx 502", 5).isEmpty());
    }
}