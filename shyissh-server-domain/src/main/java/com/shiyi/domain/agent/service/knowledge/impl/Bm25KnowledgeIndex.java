package com.shiyi.domain.agent.service.knowledge.impl;

import com.shiyi.domain.agent.adapter.repository.IWikiRepository;
import com.shiyi.domain.agent.model.entity.WikiEntryEntity;
import com.shiyi.domain.agent.model.valobj.properties.RagProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.cn.smart.HMMChineseTokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 关键词检索路：进程内 Lucene + 中文分词 + BM25。
 * <p>
 * <b>为什么换掉 2-gram 稀疏向量</b>：稀疏向量能保精确串，但关键词腿的排序质量只到"idf 加权词袋"，
 * 没有文档长度归一，长 runbook 与短命令片段在同一量纲里比会系统性压短段；
 * 而 BM25（k1=1.2、b=0.75）把"词频饱和 + 长度归一"一起补上，且能直接用 Lucene 的分析链。
 * <p>
 * <b>为什么是进程内而不是 ES</b>：语料是 70 段随应用发布的 md + 运行期少量 wiki，
 * 一个几千文档的倒排索引在内存里只有几十 KB~几 MB，起一个 ES 集群只会多一套要运维、要备份、
 * 要保证与 MySQL 一致的组件；索引本身是<b>可重建的派生数据</b>，进程重启就重建，不需要持久化。
 * <p>
 * <b>中文分词为什么用 SmartChinese</b>：它随 Lucene 发布（无额外模型文件、无词典下载），
 * 是"分词 + 词性/停用词"一体的小型 HMM+词典实现；选它不是为了极致效果，而是为了
 * "零运维成本地获得真正的词级索引"。ASCII/命令串另用空白分词（{@link CodeAnalyzer}），
 * 避免 {@code df -h}、{@code nginx.conf} 被中文分词器切碎。
 * <p>
 * <b>一致性</b>：索引与查询用同一份 {@link #analyzer}，避免"索引时切开、查询时不切开"导致的静默漏召回。
 * 写入与查询分两个通道：写走 {@link IndexWriter}，读走 {@link SearcherManager}（refresh 后新文档立刻可见）。
 *
 * @author shiyi
 */
@Service
public class Bm25KnowledgeIndex {

    private static final Logger log = LoggerFactory.getLogger(Bm25KnowledgeIndex.class);

    private static final String F_DOC_ID = "doc_id";
    private static final String F_TITLE = "title";
    private static final String F_BODY = "body";
    private static final String F_COMMANDS = "commands";
    private static final String F_TAGS = "tags";
    private static final String F_KIND = "kind";
    private static final String F_SOURCE = "source";
    private static final String F_SNIPPET = "snippet";

    /** 标题命中比正文重要：标题是"这一节讲什么"，正文里可能只是顺带提一句。 */
    private static final float TITLE_BOOST = 3.0f;
    /** 命令块是精确匹配最强的信号（{@code df -h}/{@code systemctl restart nginx}）。 */
    private static final float COMMAND_BOOST = 2.0f;
    /** 标签是人工维护的检索提示词。 */
    private static final float TAG_BOOST = 1.5f;

    @Resource
    private RagProperties ragProperties;

    @Resource
    private IWikiRepository wikiRepository;

    /** 中文正文/标题用 HMM 中文分词；命令/标签用空白分词，保住 {@code df -h} 这种整串。 */
    private final Analyzer analyzer = new PerFieldAnalyzerWrapper(new ChineseAnalyzer(),
            Map.of(F_COMMANDS, new CodeAnalyzer(), F_TAGS, new CodeAnalyzer()));

    private final Directory directory = new ByteBuffersDirectory();

    /** docId → 元信息：命中后回填给检索器用，避免为了拿标题/摘要再回表。 */
    private final Map<String, Doc> docs = new ConcurrentHashMap<>();

    private IndexWriter writer;

    private SearcherManager searcherManager;

    @PostConstruct
    public void init() {
        try {
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            // BM25 参数直接来自配置：避免引入第二套"相似度配置"概念，配置一处就够
            float k1Value = ragProperties == null ? 1.2f : ragProperties.getKeyword().getK1();
            float bValue = ragProperties == null ? 0.75f : ragProperties.getKeyword().getB();
            config.setSimilarity(new BM25Similarity(Math.max(0f, k1Value), Math.min(1f, Math.max(0f, bValue))));
            this.writer = new IndexWriter(directory, config);
            this.searcherManager = new SearcherManager(writer, null);
            log.info("[bm25] 关键词索引已就绪（k1={} b={}）", k1Value, bValue);
        } catch (Exception e) {
            // 关键词路是可降级的增强项：建索引失败只记日志，检索侧会自动退回稀疏路
            log.warn("[bm25] 关键词索引初始化失败，关键词腿将降级：{}", e.getMessage(), e);
            this.writer = null;
            this.searcherManager = null;
        }
    }

    @PreDestroy
    public void destroy() {
        closeQuietly(searcherManager);
        closeQuietly(writer);
        closeQuietly(directory);
    }

    /**
     * 是否可用：配置开启、索引已建成、且当前确实有文档。
     */
    public boolean available() {
        if (ragProperties != null && !ragProperties.getKeyword().isEnabled()) {
            return false;
        }
        return writer != null && searcherManager != null && !docs.isEmpty();
    }

    /**
     * 全量重建 md 静态知识（每次同步都重建：md 是静态源，量很小，全量比增量更不容易错）。
     */
    public synchronized void rebuildMd(List<MdKnowledgeLoader.Section> sections) {
        if (writer == null || sections == null) {
            return;
        }
        try {
            writer.deleteAll();
            docs.keySet().removeIf(id -> !id.startsWith("wiki:"));
            for (MdKnowledgeLoader.Section section : sections) {
                addMd(section);
            }
            commitAndRefresh();
            log.info("[bm25] md 知识索引重建完成：{} 条", sections.size());
        } catch (Exception e) {
            log.warn("[bm25] md 知识索引重建失败（检索将退回稀疏路）：{}", e.getMessage(), e);
        }
    }

    /**
     * wiki 是运行期数据：写入时按 id upsert，索引随写随新。
     */
    public synchronized void upsertWiki(List<WikiEntryEntity> rows) {
        if (writer == null || rows == null || rows.isEmpty()) {
            return;
        }
        try {
            for (WikiEntryEntity row : rows) {
                addWiki(row);
            }
            commitAndRefresh();
        } catch (Exception e) {
            log.warn("[bm25] wiki 索引更新失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 启动时重建 wiki 部分：md 由 {@code KnowledgeSourceSync} 重建，wiki 在 MySQL 里，
     * 进程重启后内存索引是空的，必须按主键游标扫一遍。
     */
    public void rebuildWiki() {
        if (writer == null || wikiRepository == null) {
            return;
        }
        try {
            long afterId = 0L;
            int scanned = 0;
            while (true) {
                List<WikiEntryEntity> rows = wikiRepository.listForIndex(afterId, 200);
                if (rows == null || rows.isEmpty()) {
                    break;
                }
                for (WikiEntryEntity row : rows) {
                    addWiki(row);
                    if (row.getId() != null && row.getId() > afterId) {
                        afterId = row.getId();
                    }
                    scanned++;
                }
                if (rows.size() < 200) {
                    break;
                }
            }
            commitAndRefresh();
            if (scanned > 0) {
                log.info("[bm25] wiki 索引重建完成：{} 条", scanned);
            }
        } catch (Exception e) {
            log.warn("[bm25] wiki 索引重建失败（wiki 关键词召回暂不可用）：{}", e.getMessage(), e);
        }
    }

    /**
     * 关键词召回：中文分词 + BM25 打分，返回按分数降序的文档元信息。
     *
     * @param query 已归一化的查询串
     * @param topK  召回条数
     */
    public List<Doc> search(String query, int topK) {
        if (!available() || query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        try {
            Set<String> terms = analyzeTerms(query);
            if (terms.isEmpty()) {
                return List.of();
            }
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            for (String term : terms) {
                builder.add(new BoostQuery(new TermQuery(new Term(F_TITLE, term)), TITLE_BOOST),
                        BooleanClause.Occur.SHOULD);
                builder.add(new TermQuery(new Term(F_BODY, term)), BooleanClause.Occur.SHOULD);
                builder.add(new BoostQuery(new TermQuery(new Term(F_COMMANDS, term)), COMMAND_BOOST),
                        BooleanClause.Occur.SHOULD);
                builder.add(new BoostQuery(new TermQuery(new Term(F_TAGS, term)), TAG_BOOST),
                        BooleanClause.Occur.SHOULD);
            }
            builder.setMinimumNumberShouldMatch(1);
            Query q = builder.build();
            IndexSearcher searcher = searcherManager.acquire();
            try {
                ScoreDoc[] scoreDocs = searcher.search(q, topK).scoreDocs;
                List<Doc> result = new ArrayList<>(scoreDocs.length);
                for (ScoreDoc scoreDoc : scoreDocs) {
                    Document document = searcher.storedFields().document(scoreDoc.doc);
                    String docId = document.get(F_DOC_ID);
                    Doc doc = docId == null ? null : docs.get(docId);
                    if (doc != null) {
                        result.add(doc.withScore(scoreDoc.score));
                    }
                }
                return result;
            } finally {
                searcherManager.release(searcher);
            }
        } catch (Exception e) {
            log.warn("[bm25] 关键词检索失败（本次退回单路召回）：{}", e.getMessage());
            return List.of();
        }
    }

    private void addMd(MdKnowledgeLoader.Section section) {
        String docId = section.docId();
        docs.put(docId, new Doc(docId, section.title(), snippet(section.content()),
                section.commands(), section.tags(), section.kind(), "md", 0d));
        writeDocument(docId, section.title(), section.content(), section.commands(),
                section.tags(), section.kind(), "md");
    }

    private void addWiki(WikiEntryEntity row) {
        if (row == null || row.getId() == null) {
            return;
        }
        String docId = "wiki:" + row.getId();
        String content = row.getContent() == null ? "" : row.getContent();
        String kind = row.getSource() == null || row.getSource().isBlank() ? "wiki" : row.getSource();
        docs.put(docId, new Doc(docId, row.getTitle(), snippet(content),
                null, row.getTags(), kind, "wiki", 0d));
        writeDocument(docId, row.getTitle(), content, null, row.getTags(), kind, "wiki");
    }

    private void writeDocument(String docId, String title, String content,
                               String commands, String tags, String kind, String source) {
        Document document = new Document();
        // doc_id 用 StringField（不分词、精确匹配），updateDocument 靠它做覆盖写
        document.add(new StringField(F_DOC_ID, docId, Field.Store.YES));
        document.add(new TextField(F_TITLE, nullSafe(title), Field.Store.NO));
        document.add(new TextField(F_BODY, nullSafe(content), Field.Store.NO));
        if (commands != null && !commands.isBlank()) {
            document.add(new TextField(F_COMMANDS, commands, Field.Store.NO));
        }
        if (tags != null && !tags.isBlank()) {
            document.add(new TextField(F_TAGS, tags, Field.Store.NO));
        }
        document.add(new StoredField(F_KIND, nullSafe(kind)));
        document.add(new StoredField(F_SOURCE, nullSafe(source)));
        try {
            writer.updateDocument(new Term(F_DOC_ID, docId), document);
        } catch (Exception e) {
            log.warn("[bm25] 写入文档 {} 失败：{}", docId, e.getMessage());
        }
    }

    private void commitAndRefresh() throws Exception {
        writer.commit();
        searcherManager.maybeRefreshBlocking();
    }

    /**
     * 用两套分析链取 token 并集：中文链负责"磁盘/索引/节点"，ASCII 链负责"nginx/502/df -h"。
     * 两套都保留是刻意的——只用中文链会把 {@code nginx.conf} 切成 {@code nginx}/{@code conf} 而丢整串，
     * 只用 ASCII 链又切不动中文。
     */
    private Set<String> analyzeTerms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        collect(analyzer, text, terms);
        collect(new CodeAnalyzer(), text, terms);
        return terms;
    }

    private static void collect(Analyzer analyzer, String text, Set<String> sink) {
        try (TokenStream stream = analyzer.tokenStream(F_BODY, text)) {
            CharTermAttribute attribute = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                String term = attribute.toString();
                if (term == null) {
                    continue;
                }
                term = term.trim().toLowerCase(Locale.ROOT);
                if (term.length() >= 2) {
                    sink.add(term);
                }
            }
            stream.end();
        } catch (Exception e) {
            // 单套分析链失败不影响另一套
        }
    }

    private String snippet(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        int max = ragProperties == null ? 300 : ragProperties.getRetrieval().getSnippetChars();
        String folded = content.replaceAll("\\s+", " ").trim();
        return folded.length() <= max ? folded : folded.substring(0, max) + "…";
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static void closeQuietlyAuto(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 关闭失败不影响进程退出
        }
    }

    private static void closeQuietly(IndexWriter closeable) {
        closeQuietlyAuto(closeable);
    }

    private static void closeQuietly(SearcherManager closeable) {
        closeQuietlyAuto(closeable);
    }

    private static void closeQuietly(Directory closeable) {
        closeQuietlyAuto(closeable);
    }

    /**
     * 一条可召回的文档元信息：检索器拿到后直接组装 Hit，不再回表。
     */
    public record Doc(String docId, String title, String snippet, String commands, String tags,
                      String kind, String source, double score) {

        Doc withScore(double newScore) {
            return new Doc(docId, title, snippet, commands, tags, kind, source, newScore);
        }
    }

    /** 中文链：HMM 中文分词 + 小写归一。 */
    private static final class ChineseAnalyzer extends Analyzer {

        @Override
        protected TokenStreamComponents createComponents(String fieldName) {
            Tokenizer tokenizer = new HMMChineseTokenizer();
            return new TokenStreamComponents(tokenizer, new LowerCaseFilter(tokenizer));
        }
    }

    /**
     * ASCII/命令链：只按空白切，保留 {@code df -h}、{@code nginx.conf}、{@code systemctl restart}。
     * 命令块本来就是一命令一行，按空白切已经足够，过度切分只会把精确串拆散。
     */
    private static final class CodeAnalyzer extends Analyzer {

        @Override
        protected TokenStreamComponents createComponents(String fieldName) {
            Tokenizer tokenizer = new WhitespaceTokenizer();
            return new TokenStreamComponents(tokenizer, new LowerCaseFilter(tokenizer));
        }
    }
}