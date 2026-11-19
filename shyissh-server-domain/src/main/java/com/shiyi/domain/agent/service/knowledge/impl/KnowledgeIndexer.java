package com.shiyi.domain.agent.service.knowledge.impl;

import com.shiyi.domain.agent.adapter.embedding.EmbeddingClient;
import com.shiyi.domain.agent.adapter.repository.IWikiRepository;
import com.shiyi.domain.agent.adapter.vector.SparseVector;
import com.shiyi.domain.agent.adapter.vector.VectorStore;
import com.shiyi.domain.agent.model.entity.WikiEntryEntity;
import com.shiyi.domain.agent.model.valobj.properties.RagProperties;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识索引：把静态知识（{@code docs/knowledge-base} 切分后的 md）与 Agent 沉淀经验（{@code agent_wiki}）
 * 一起写进同一个混合集合。
 * <p>
 * <b>为什么 wiki 也进向量库</b>：wiki 是运行期随时写入的，以前"入库时还没有向量"只能走 MySQL FULLTEXT；
 * 现在索引是全量重算的（按 id 游标扫全表、覆盖写），wiki 不再有"写的时候没向量"的问题。
 * 两条来源写进同一集合并用 payload {@code source} 区分，检索侧一次请求就能同时召回，
 * 不必再维护"两张表两套检索、分数怎么合并"的复杂度。
 * <p>
 * <b>为什么稠密与稀疏用不同长度的文本</b>：稠密向量喂太长会稀释主题（且 embedding 有 max-chars），
 * 稀疏向量恰恰相反——命令、错误码这些精确串越全越好，所以稀疏用全文（含命令与标签）。
 * <p>
 * 幂等：md 与 wiki 都用确定性点 id 覆盖写，重复同步不会产生重复点；
 * 源头（md 文件）删掉一节时，下一次同步把它从集合里删掉——
 * 所以 {@code md} 的 payload 必须带全文：检索时不再回表，源文件就是唯一真相。
 * wiki 仍以 MySQL 为准，回表取最新正文。
 *
 * @author shiyi
 */
@Service
public class KnowledgeIndexer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexer.class);

    /** payload 里给模型看的预览长度：只给 rerank 与日志看，正文另存 {@code content} 字段。 */
    private static final int PREVIEW_CHARS = 500;

    /** DashScope embedding 单次最多 10 条，超了整批报错。 */
    private static final int MAX_EMBED_BATCH = 10;

    private static final int WIKI_BATCH = 200;

    @Resource
    private IWikiRepository wikiRepository;

    @Resource
    private EmbeddingClient embeddingClient;

    @Resource
    private RagProperties ragProperties;

    @Resource
    private ObjectProvider<VectorStore> vectorStoreProvider;

    /**
     * 混合集合名：前缀 + {@code _knowledge_hybrid_} + 维度。
     * <p>
     * 带 {@code hybrid} 与维度两个段是刻意的：换 embedding 模型时天然隔离；
     * 旧的纯稠密集合原样保留，回滚只需把读路径指回去，不需要重建数据。
     */
    public String hybridCollectionName() {
        return ragProperties.getVector().getCollectionPrefix() + "_knowledge_hybrid_"
                + ragProperties.getEmbedding().getDimensions();
    }

    /**
     * 全量重建 wiki 索引（静态知识由 {@code KnowledgeSourceSync} 按 md 源写入）。
     *
     * @param batchSize 每批条数，0 取配置里的 batch-size
     * @return 成功写入向量库的条数
     */
    public int indexPending(int batchSize) {
        if (!ragProperties.getVector().isEnabled() || !embeddingClient.available()) {
            log.warn("[knowledge] 向量化未启用或 embedding 不可用，跳过索引");
            return 0;
        }
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            log.warn("[knowledge] 未找到 VectorStore 实现，跳过索引");
            return 0;
        }
        int dimensions = embeddingClient.dimensions();
        String collection = hybridCollectionName();
        vectorStore.createHybridCollectionIfAbsent(collection, dimensions);
        vectorStore.ensurePayloadIndexes(collection);

        return indexWiki(vectorStore, collection, dimensions);
    }

    private int indexWiki(VectorStore vectorStore, String collection, int dimensions) {
        long afterId = 0L;
        int indexed = 0;
        while (true) {
            List<WikiEntryEntity> rows = wikiRepository.listForIndex(afterId, WIKI_BATCH);
            if (rows == null || rows.isEmpty()) {
                break;
            }
            List<String> fullTexts = new ArrayList<>(rows.size());
            List<String> denseTexts = new ArrayList<>(rows.size());
            for (WikiEntryEntity row : rows) {
                String text = buildWikiText(row);
                fullTexts.add(text);
                denseTexts.add(truncate(text, ragProperties.getEmbedding().getMaxChars()));
            }
            List<float[]> vectors = embeddingClient.embedBatch(denseTexts);
            if (vectors == null || vectors.size() != rows.size()) {
                log.warn("[knowledge] wiki 批量向量化失败，本轮 wiki 索引中止，已处理 {} 条", indexed);
                break;
            }
            List<VectorStore.HybridPoint> points = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                WikiEntryEntity row = rows.get(i);
                afterId = Math.max(afterId, row.getId());
                float[] vector = vectors.get(i);
                if (vector == null || vector.length != dimensions) {
                    log.warn("[knowledge] wiki {} 向量化结果异常，跳过（下次全量索引会重试）", row.getId());
                    continue;
                }
                Map<String, Object> payload = new HashMap<>();
                payload.put("id", row.getId());
                payload.put("doc_id", "wiki:" + row.getId());
                payload.put("kind", "wiki");
                payload.put("title", row.getTitle());
                payload.put("source", "wiki");
                payload.put("scope", nullSafe(row.getUserId()).isEmpty() ? "GLOBAL" : "USER");
                payload.put("user_id", nullSafe(row.getUserId()));
                payload.put("host_key", nullSafe(row.getHostKey()));
                payload.put("preview", truncate(fullTexts.get(i), PREVIEW_CHARS));
                points.add(new VectorStore.HybridPoint("wiki:" + row.getId(), vector,
                        SparseEncoder.encode(fullTexts.get(i)), payload));
            }
            if (!points.isEmpty()) {
                vectorStore.upsertHybrid(collection, points);
                indexed += points.size();
            }
            if (rows.size() < WIKI_BATCH) {
                break;
            }
        }
        if (indexed > 0) {
            log.info("[knowledge] wiki 已索引 {} 条（集合 {}）", indexed, collection);
        }
        return indexed;
    }

    /**
     * 按主键索引一批 wiki（写入路径的即时索引用）。
     * <p>
     * wiki 是运行期不断追加的，等全量扫描太慢；写入后把 id 交给后台 worker，
     * 就能在秒级内被检索到。点 id 由业务主键派生，重复索引是覆盖写，幂等。
     *
     * @param ids wiki 主键
     * @return 成功写入向量库的条数
     */
    public int indexWikiByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty() || !ragProperties.getVector().isEnabled() || !embeddingClient.available()) {
            return 0;
        }
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            return 0;
        }
        List<WikiEntryEntity> rows = wikiRepository.findByIds(ids);
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int dimensions = embeddingClient.dimensions();
        String collection = hybridCollectionName();
        vectorStore.createHybridCollectionIfAbsent(collection, dimensions);
        vectorStore.ensurePayloadIndexes(collection);
        return writeWikiPoints(vectorStore, collection, dimensions, rows);
    }

    private int writeWikiPoints(VectorStore vectorStore, String collection, int dimensions,
                                List<WikiEntryEntity> rows) {
        List<String> fullTexts = new ArrayList<>(rows.size());
        List<String> denseTexts = new ArrayList<>(rows.size());
        for (WikiEntryEntity row : rows) {
            String text = buildWikiText(row);
            fullTexts.add(text);
            denseTexts.add(truncate(text, ragProperties.getEmbedding().getMaxChars()));
        }
        List<float[]> vectors = embeddingClient.embedBatch(denseTexts);
        if (vectors == null || vectors.size() != rows.size()) {
            log.warn("[knowledge] wiki 批量向量化失败，本批 {} 条跳过", rows.size());
            return 0;
        }
        List<VectorStore.HybridPoint> points = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            WikiEntryEntity row = rows.get(i);
            float[] vector = vectors.get(i);
            if (vector == null || vector.length != dimensions) {
                log.warn("[knowledge] wiki {} 向量化结果异常，跳过（下次全量索引会重试）", row.getId());
                continue;
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("id", row.getId());
            payload.put("doc_id", "wiki:" + row.getId());
            payload.put("kind", "wiki");
            payload.put("title", row.getTitle());
            payload.put("source", "wiki");
            payload.put("scope", nullSafe(row.getUserId()).isEmpty() ? "GLOBAL" : "USER");
            payload.put("user_id", nullSafe(row.getUserId()));
            payload.put("host_key", nullSafe(row.getHostKey()));
            payload.put("preview", truncate(fullTexts.get(i), PREVIEW_CHARS));
            points.add(new VectorStore.HybridPoint("wiki:" + row.getId(), vector,
                    SparseEncoder.encode(fullTexts.get(i)), payload));
        }
        if (points.isEmpty()) {
            return 0;
        }
        vectorStore.upsertHybrid(collection, points);
        return points.size();
    }

    /**
     * 稀疏路用的全文：标题 + 正文 + 命令 + 标签。
     * <p>
     * 不截断：命令与错误码是最强的精确匹配信号，截掉尾部的命令块等于砍掉最有用的一半。
     */
    private String buildWikiText(WikiEntryEntity row) {
        StringBuilder text = new StringBuilder();
        text.append(nullSafe(row.getTitle()));
        if (notBlank(row.getContent())) {
            text.append('\n').append(row.getContent());
        }
        if (notBlank(row.getTags())) {
            text.append("\n标签：").append(row.getTags());
        }
        return text.toString();
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return maxChars > 0 && text.length() > maxChars ? text.substring(0, maxChars) : text;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 把 md 静态知识目录切成条目后写进混合集合（幂等：upsert + 清理已删除条目）。
     * <p>
     * <b>为什么不在数据库存一份</b>：这些 md 是随应用发布的静态文件，检索只需要"编号 + 全文"，
     * 存库只会多一份会漂移的副本（此前 {@code ops_knowledge} 就是手抄的 70 行，早已与 md 不一致）。
     * 现在 payload 直接带正文，命中即可用，省掉一次回表。
     * <p>
     * 清理策略：先用 {@code listDocIds(collection, "md")} 拿索引里现有的 doc_id，
     * 与本次切出来的集合求差，差集删除。这样"删掉 md 里的一节"能在下次同步时反映到索引。
     *
     * @param dir md 目录
     * @return 本次统计（切出条数 / 写入条数 / 删除条数 / 耗时）
     */
    public SyncStats syncMarkdown(Path dir) {
        long startedAt = System.currentTimeMillis();
        if (!ragProperties.getVector().isEnabled()) {
            log.warn("[knowledge] 向量检索未启用，跳过 md 同步");
            return new SyncStats(0, 0, 0, System.currentTimeMillis() - startedAt);
        }
        // embedding 不可用时的降级：只写稀疏向量（2-gram 本地可算），检索仍有 BM25 级别的命中；
        // 等 embedding 恢复后重跑一次同步，同一个点 id 会把 dense 补齐。
        boolean denseAvailable = embeddingClient.available();
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            log.warn("[knowledge] 未找到 VectorStore 实现，跳过 md 同步");
            return new SyncStats(0, 0, 0, System.currentTimeMillis() - startedAt);
        }
        List<MdKnowledgeLoader.Section> sections = MdKnowledgeLoader.loadAll(dir);
        if (sections.isEmpty()) {
            log.warn("[knowledge] md 目录 {} 下没有切出知识（检查 app.rag.knowledge.dir）", dir);
            return new SyncStats(0, 0, 0, System.currentTimeMillis() - startedAt);
        }
        if (!denseAvailable) {
            log.warn("[knowledge] embedding 不可用，md 只写稀疏向量；召回会退化到关键词级别，恢复后请重跑同步");
        }
        int dimensions = embeddingClient.dimensions();
        String collection = hybridCollectionName();
        vectorStore.createHybridCollectionIfAbsent(collection, dimensions);
        vectorStore.ensurePayloadIndexes(collection);

        int batchSize = Math.max(1, Math.min(MAX_EMBED_BATCH, ragProperties.getEmbedding().getBatchSize()));
        int indexed = 0;
        // 中途失败时不能做差集删除：没写进去不等于源里已删掉
        boolean aborted = false;
        Set<String> currentIds = new HashSet<>();
        for (int from = 0; from < sections.size(); from += batchSize) {
            List<MdKnowledgeLoader.Section> chunk = sections.subList(from,
                    Math.min(sections.size(), from + batchSize));
            List<String> denseTexts = new ArrayList<>(chunk.size());
            List<String> fullTexts = new ArrayList<>(chunk.size());
            for (MdKnowledgeLoader.Section section : chunk) {
                String fullText = buildSectionText(section);
                fullTexts.add(fullText);
                denseTexts.add(truncate(fullText, ragProperties.getEmbedding().getMaxChars()));
            }
            List<float[]> vectors = denseAvailable ? embeddingClient.embedBatch(denseTexts) : null;
            if (denseAvailable && (vectors == null || vectors.size() != chunk.size())) {
                log.warn("[knowledge] md 批量向量化失败（本批 {} 条），本次同步中止；下次同步会重写", chunk.size());
                aborted = true;
                break;
            }
            List<VectorStore.HybridPoint> points = new ArrayList<>(chunk.size());
            for (int i = 0; i < chunk.size(); i++) {
                MdKnowledgeLoader.Section section = chunk.get(i);
                float[] vector = denseAvailable ? vectors.get(i) : null;
                if (denseAvailable && (vector == null || vector.length != dimensions)) {
                    log.warn("[knowledge] md {} 向量化结果异常，跳过", section.docId());
                    continue;
                }
                currentIds.add(section.docId());
                points.add(new VectorStore.HybridPoint("md:" + section.docId(), vector,
                        SparseEncoder.encode(fullTexts.get(i)), sectionPayload(section, fullTexts.get(i))));
            }
            if (!points.isEmpty()) {
                vectorStore.upsertHybrid(collection, points);
                indexed += points.size();
            }
        }

        int deleted = 0;
        if (aborted) {
            log.warn("[knowledge] 本次同步中途失败（已写 {} 条），跳过陈旧清理，避免误删", indexed);
        } else {
            List<String> staleIds = new ArrayList<>();
            for (String docId : vectorStore.listDocIds(collection, "md")) {
                if (!currentIds.contains(docId)) {
                    staleIds.add("md:" + docId);
                }
            }
            if (!staleIds.isEmpty()) {
                vectorStore.delete(collection, staleIds);
                deleted = staleIds.size();
            }
        }
        SyncStats stats = new SyncStats(sections.size(), indexed, deleted, System.currentTimeMillis() - startedAt);
        log.info("[knowledge] md 同步完成：切出 {} 条，写入 {} 条，清理 {} 条，耗时 {} ms",
                stats.sections(), stats.indexed(), stats.deleted(), stats.elapsedMs());
        return stats;
    }

    private Map<String, Object> sectionPayload(MdKnowledgeLoader.Section section, String fullText) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", section.docId());
        payload.put("doc_id", section.docId());
        payload.put("kind", section.kind());
        payload.put("title", section.title());
        payload.put("content", section.content());
        payload.put("commands", section.commands());
        payload.put("tags", section.tags());
        payload.put("severity", section.severity());
        payload.put("source", "md");
        payload.put("scope", "GLOBAL");
        payload.put("path", section.path());
        payload.put("content_hash", section.contentHash());
        payload.put("preview", truncate(fullText, PREVIEW_CHARS));
        return payload;
    }

    /**
     * 稀疏用全文（标题 + 正文 + 命令 + 标签），稠密再按 maxChars 截——理由见类注释。
     */
    private static String buildSectionText(MdKnowledgeLoader.Section section) {
        StringBuilder builder = new StringBuilder();
        builder.append(section.title()).append('\n').append(section.content());
        if (!section.commands().isBlank()) {
            builder.append("\n命令:\n").append(section.commands());
        }
        if (!section.tags().isBlank()) {
            builder.append("\n标签:").append(section.tags());
        }
        return builder.toString();
    }

    /**
     * 一次 md 同步的统计。
     */
    public record SyncStats(int sections, int indexed, int deleted, long elapsedMs) {
    }

}