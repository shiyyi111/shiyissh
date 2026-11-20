package com.shiyi.domain.agent.service.knowledge.impl;

import com.shiyi.domain.agent.adapter.embedding.EmbeddingClient;
import com.shiyi.domain.agent.adapter.repository.IWikiRepository;
import com.shiyi.domain.agent.adapter.vector.SparseVector;
import com.shiyi.domain.agent.adapter.vector.VectorStore;
import com.shiyi.domain.agent.model.entity.WikiEntryEntity;
import com.shiyi.domain.agent.model.valobj.properties.RagProperties;
import com.shiyi.domain.agent.service.knowledge.KnowledgeRetriever;
import com.shiyi.domain.agent.service.knowledge.LlmQueryRewriter;
import com.shiyi.domain.agent.service.knowledge.QueryNormalizer;
import com.shiyi.domain.agent.service.knowledge.RerankService;
import com.shiyi.domain.agent.service.knowledge.RrfFusion;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识检索主链路：一次混合查询同时走稠密 + 稀疏两路，由向量库在服务端做 RRF 融合。
 * <p>
 * <b>为什么不再自己合并两路</b>：旧实现是"MySQL 关键词一路 + Qdrant 向量一路，两路在 JVM 里手写 RRF"，
 * 问题是两张表两套检索、分数口径不同、wiki 还因为"入库时没有向量"被排除在向量路之外。
 * 现在 wiki 与权威知识同在一个混合集合里，稠密/稀疏两路由 Qdrant 一次 {@code /points/query} 完成，
 * 客户端只发两个查询向量，名次与融合都在服务端算完——少一次外呼、少一套要对齐的分数量纲。
 * <p>
 * <b>稀疏路为什么用 2-gram</b>：见 {@link SparseEncoder}。要点是精确串（{@code nginx 502}、{@code df -h}、
 * {@code Connection refused}）在稠密向量里会被同义化，稀疏词项能保证"命中就是命中"。
 * <p>
 * <b>降级</b>：向量库不可用（未启用 / 连接失败 / 集合不存在 / fail-open=true）时返回空结果并置
 * {@code degraded=true}。这是刻意不再回退 MySQL 关键词：两套检索并存时"到底哪套在起作用"永远说不清，
 * 而知识检索本就是增强能力，明确告知"这次没查到"比悄悄用一个口径不同的通道更可信。
 * {@code app.rag.vector.fail-open=false} 时则直接抛错，让压测/联调能暴露问题。
 *
 * @author shiyi
 */
@Service
public class HybridKnowledgeRetriever implements KnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridKnowledgeRetriever.class);

    /** 回表时批量取的上限：向量库最多返回 vector-top-k 条，回表不可能超过这个数。 */
    private static final int MAX_LOOKUP = 200;

    @Resource
    private QueryNormalizer queryNormalizer;

    @Resource
    private IWikiRepository wikiRepository;

    @Resource
    private EmbeddingClient embeddingClient;

    @Resource
    private RagProperties ragProperties;

    @Resource
    private KnowledgeIndexer knowledgeIndexer;

    /**
     * 用 ObjectProvider 而不是直接注入：{@code app.rag.vector.enabled=false} 时容器里可能根本没有
     * VectorStore 实现，直接注入会让应用起不来 —— 而"关掉向量"必须是能安全落地的开关。
     */
    @Resource
    private ObjectProvider<VectorStore> vectorStoreProvider;

    /**
     * 模型重排（可选）。同样用 ObjectProvider：没有实现（未配 key、被排除）时应静默跳过，
     * 不能让"重排不可用"变成"应用起不来"。
     * <p>
     * 只在这里用，<b>记忆召回路径不得调用</b>——那是每轮两次的热路径（R13 有界）。
     */
    @Resource
    private ObjectProvider<RerankService> rerankServiceProvider;

    /**
     * 口语化查询的兜底：首轮召回不够时才用它再查一次。
     * 允许为 null（单测手工 new 时不装配 Spring），此时改写能力相当于关闭。
     */
    @Resource
    private LlmQueryRewriter llmQueryRewriter;

    /**
     * 关键词腿（Lucene/BM25）。
     * 允许为 null：单测手工 new 且未装配时退回稀疏向量路；生产环境缺失也不至于让检索整体不可用。
     */
    @Resource
    private Bm25KnowledgeIndex bm25Index;

    @Override
    public Result retrieve(String userId, String hostKey, String query, int limit) {
        if (!ragProperties.isEnabled()) {
            // 总开关关掉时直接返回空结果：连归一化与 embedding 都不做，避免"以为关了其实还在发请求"
            return new Result(List.of(), query == null ? "" : query, false, 0L, false);
        }
        long startedAt = System.currentTimeMillis();
        int finalLimit = limit <= 0 ? ragProperties.getRetrieval().getFinalTopK() : limit;
        QueryNormalizer.Normalized normalized = queryNormalizer.normalize(query);
        if (normalized == null || normalized.isEmpty()) {
            return Result.of(List.of(), query, false, 0L);
        }

        RetrievalRound round = gather(userId, hostKey, normalized, finalLimit);
        List<Hit> hits = round.hits();
        boolean rewritten = false;
        boolean degraded = round.degraded();

        // 首轮召回不足才动用模型：改写要花一次调用，而多数查询用同义词表就够了。
        // 判定放在这里而不是 gather 里，是因为"要不要改写"是策略、不是检索本身的一部分。
        int triggerMinHits = ragProperties.getRewrite().getTriggerMinHits();
        if (!degraded && hits.size() < triggerMinHits && llmQueryRewriter != null && llmQueryRewriter.available()) {
            String rewriteQuery = llmQueryRewriter.rewrite(normalized.normalized());
            if (rewriteQuery != null && !rewriteQuery.isBlank()) {
                QueryNormalizer.Normalized rewrittenNormalized = queryNormalizer.normalize(rewriteQuery);
                if (rewrittenNormalized != null && !rewrittenNormalized.isEmpty()) {
                    RetrievalRound second = gather(userId, hostKey, rewrittenNormalized, finalLimit);
                    hits = merge(hits, second.hits(), finalLimit);
                    rewritten = true;
                    degraded = degraded || second.degraded();
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        if (degraded) {
            log.warn("[knowledge] query=\"{}\" 知识库不可用，降级返回空结果 耗时={}ms", query, elapsed);
        } else {
            log.info("[knowledge] query=\"{}\" 归一化=\"{}\" 改写={} 命中={} 耗时={}ms",
                    query, normalized.normalized(), rewritten, hits.size(), elapsed);
        }
        return new Result(hits, normalized.normalized(), rewritten, elapsed, degraded);
    }

    /**
     * 跑一轮召回：稠密 + 稀疏 → 服务端融合 → 回表取正文 → 词面覆盖率重排。
     * 抽成独立方法是为了让"改写前后各查一次"共用同一套逻辑，避免两份会走样的实现。
     */
    private RetrievalRound gather(String userId, String hostKey,
                                  QueryNormalizer.Normalized normalized, int finalLimit) {
        if (!ragProperties.getVector().isEnabled()) {
            return new RetrievalRound(List.of(), true);
        }
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            log.warn("[knowledge] 未找到 VectorStore 实现，知识检索降级");
            return new RetrievalRound(List.of(), true);
        }
        try {
            // 稠密查询向量需要外呼 embedding；这条外呼失败（欠费、超时、key 失效）不该连累精确串召回。
            // 稀疏路完全本地计算（2-gram + FNV），只要索引在就能查——所以这里允许"只有稀疏"的一路召回，
            // 代价是丢掉同义改写能力，换来的是 embedding 服务故障时知识检索仍然可用。
            float[] queryVector = embeddingClient.available() ? embeddingClient.embed(normalized.normalized()) : null;
            boolean denseAvailable = queryVector != null && queryVector.length > 0;
            String collection = knowledgeIndexer.hybridCollectionName();
            if (useBm25()) {
                // 关键词腿换成 BM25 之后，融合不再依赖稀疏向量：
                // 稠密路仍查 Qdrant，关键词路查本地 Lucene，融合在本地按名次做。
                return gatherBm25(userId, hostKey, normalized, finalLimit, vectorStore, collection,
                        queryVector, denseAvailable);
            }
            SparseVector sparse = SparseEncoder.encodeQuery(normalized.normalized());
            if (!denseAvailable && (sparse == null || sparse.isEmpty())) {
                return new RetrievalRound(List.of(), true);
            }
            if (!denseAvailable) {
                log.warn("[knowledge] 稠密查询向量不可用（embedding 已关闭或调用失败），本次仅走稀疏召回：\"{}\"",
                        normalized.normalized());
            }
            int topK = Math.max(finalLimit, ragProperties.getRetrieval().getVectorTopK());
            List<VectorStore.Hit> raw = vectorStore.queryHybrid(collection, queryVector, sparse, topK, Map.of());
            if (raw == null || raw.isEmpty()) {
                return new RetrievalRound(List.of(), false);
            }
            List<Candidate> candidates = hydrate(userId, hostKey, raw);
            double minScore = ragProperties.getRetrieval().getMinScore();
            // 先多留几条再过重排：只拿 final-top-k 去重排，等于让模型在"已经砍掉的候选"里挑，
            // 召回阶段丢掉的正确答案永远救不回来。上限仍受 vector-top-k 约束。
            int candidateLimit = Math.min(ragProperties.getRetrieval().getVectorTopK(), Math.max(finalLimit * 4, finalLimit));
            List<Hit> hits = new ArrayList<>(Math.min(candidateLimit, candidates.size()));
            for (Candidate candidate : candidates) {
                if (hits.size() >= candidateLimit) {
                    break;
                }
                if (candidate.hit().score() < minScore) {
                    continue;
                }
                hits.add(candidate.hit());
            }
            log.info("[knowledge] 一轮召回：归一化=\"{}\" 向量库={} 回表后={} 候选={}",
                    normalized.normalized(), raw.size(), candidates.size(), hits.size());
            return new RetrievalRound(refine(normalized.normalized(), hits, normalized.terms(), finalLimit), false);
        } catch (Exception e) {
            if (!ragProperties.getVector().isFailOpen()) {
                // 配了 fail-open=false 说明用户要"检索必须成功"，那就把失败暴露出去，
                // 而不是悄悄返回空结果、让人误以为知识库真的没内容
                throw new IllegalStateException("知识检索失败（app.rag.vector.fail-open=false）：" + e.getMessage(), e);
            }
            log.warn("[knowledge] 知识检索失败，降级返回空结果：{}", e.getMessage());
            return new RetrievalRound(List.of(), true);
        }
    }

    /**
     * 关键词腿是否走 BM25：配置开启 + backend=bm25 + 索引可用。
     * 三点都满足才切；任一不满足就退回稀疏向量路，保证"换方案"不会变成"检索挂掉"。
     */
    private boolean useBm25() {
        RagProperties.Keyword keyword = ragProperties.getKeyword();
        return keyword != null && keyword.isEnabled()
                && !"sparse".equalsIgnoreCase(keyword.getBackend())
                && bm25Index != null && bm25Index.available();
    }

    /**
     * BM25 关键词腿 + 稠密向量腿的 RRF 融合。
     * <p>
     * 与稀疏路的差别只在"关键词腿怎么取候选"：稀疏路把 2-gram 向量交给 Qdrant 在服务端融合，
     * 这里改成"稠密查 Qdrant、关键词查本地 Lucene"，再在本地按名次融合。
     * 融合用 RRF 而不是分数相加：BM25 分与余弦分不在同一量纲，跨量纲相加等于给分数大的那一路隐性加权。
     */
    private RetrievalRound gatherBm25(String userId, String hostKey, QueryNormalizer.Normalized normalized,
                                      int finalLimit, VectorStore vectorStore, String collection,
                                      float[] queryVector, boolean denseAvailable) {
        RagProperties.Keyword keyword = ragProperties.getKeyword();
        int configuredKeywordTopK = ragProperties.getRetrieval().getKeywordTopK();
        int keywordTopK = keyword.getTopK() > 0 ? keyword.getTopK() : configuredKeywordTopK;
        int denseTopK = Math.max(finalLimit, ragProperties.getRetrieval().getVectorTopK());

        List<Hit> denseHits = new ArrayList<>();
        if (denseAvailable) {
            // 关键词腿已由 Lucene 负责，Qdrant 这次只走稠密单路（sparse 传 null，服务端不做融合）
            List<VectorStore.Hit> raw = vectorStore.queryHybrid(collection, queryVector, null, denseTopK, Map.of());
            if (raw != null && !raw.isEmpty()) {
                double minScore = ragProperties.getRetrieval().getMinScore();
                for (Candidate candidate : hydrate(userId, hostKey, raw)) {
                    if (candidate.hit().score() >= minScore) {
                        denseHits.add(candidate.hit());
                    }
                }
            }
        }
        denseHits.sort(Comparator.comparingDouble(Hit::score).reversed());

        List<Bm25KnowledgeIndex.Doc> docs = bm25Index.search(normalized.normalized(), keywordTopK);
        List<Hit> keywordHits = toKeywordHits(userId, hostKey, docs);
        if (denseHits.isEmpty() && keywordHits.isEmpty()) {
            return new RetrievalRound(List.of(), !denseAvailable);
        }

        int rrfK = keyword.getRrfK() > 0 ? keyword.getRrfK() : ragProperties.getRetrieval().getRrfK();
        List<RrfFusion.Scored<Hit>> fused = RrfFusion.fuse(List.of(denseHits, keywordHits), rrfK, Hit::docId);
        // 先多留几条再过重排（同稀疏路）：重排只在"已经召回的候选"里挑，召回丢掉的就救不回来
        int candidateLimit = Math.min(denseTopK + keywordTopK, Math.max(finalLimit * 4, finalLimit));
        List<Hit> merged = new ArrayList<>(Math.min(candidateLimit, fused.size()));
        for (RrfFusion.Scored<Hit> scored : fused) {
            if (merged.size() >= candidateLimit) {
                break;
            }
            Hit hit = scored.item();
            merged.add(new Hit(hit.docId(), hit.title(), hit.snippet(), hit.commands(),
                    hit.tags(), hit.kind(), hit.source(), scored.score()));
        }
        log.info("[knowledge] 一轮召回（bm25）：归一化=\"{}\" 稠密={} 关键词={} 融合={} 候选={}",
                normalized.normalized(), denseHits.size(), keywordHits.size(), fused.size(), merged.size());
        return new RetrievalRound(refine(normalized.normalized(), merged, normalized.terms(), finalLimit),
                // 关键词腿是本地索引，稠密挂了它仍能独立召回 ⇒ 只有"两路都空"才算降级
                !denseAvailable && keywordHits.isEmpty());
    }

    /**
     * Lucene 命中转 Hit，并按 wiki 归属回表：md 的标题/摘要/命令/标签都在索引里，
     * wiki 的正文仍以 MySQL 为准（归属过滤留在回表 SQL，越权判断只有一处）。
     */
    private List<Hit> toKeywordHits(String userId, String hostKey, List<Bm25KnowledgeIndex.Doc> docs) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        List<Long> wikiIds = new ArrayList<>();
        for (Bm25KnowledgeIndex.Doc doc : docs) {
            Long id = wikiIdOf(doc.docId());
            if (id != null) {
                wikiIds.add(id);
            }
        }
        Map<Long, WikiEntryEntity> wikiById = new LinkedHashMap<>();
        if (!wikiIds.isEmpty()) {
            for (WikiEntryEntity entity : safe(wikiRepository.queryByIds(cap(wikiIds), userId, hostKey))) {
                wikiById.put(entity.getId(), entity);
            }
        }
        List<Hit> hits = new ArrayList<>(docs.size());
        for (Bm25KnowledgeIndex.Doc doc : docs) {
            Long wikiId = wikiIdOf(doc.docId());
            if (wikiId != null) {
                WikiEntryEntity entity = wikiById.get(wikiId);
                if (entity == null) {
                    // 回表没找到（刚被删或归属已变）：宁可少一条，也不要越权返回
                    continue;
                }
                Hit hit = toHit(entity);
                hits.add(new Hit(hit.docId(), hit.title(), hit.snippet(), hit.commands(), hit.tags(),
                        hit.kind(), "wiki", doc.score()));
                continue;
            }
            hits.add(new Hit(doc.docId(), doc.title(), doc.snippet(), doc.commands(), doc.tags(),
                    doc.kind(), doc.source(), doc.score()));
        }
        return hits;
    }

    private static Long wikiIdOf(String docId) {
        if (docId == null || !docId.startsWith("wiki:")) {
            return null;
        }
        try {
            return Long.parseLong(docId.substring("wiki:".length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 回表：向量库只有 id 与预览，正文、可见性判断都必须回 MySQL。
     * <p>
     * 归属过滤放在回表而不是向量库 filter：{@code scope=GLOBAL OR user_id=me} 这种"或"语义
     * 用 Qdrant 的 should 也能写，但一旦写错就是"越权检索"这种静默事故；
     * 回表用的还是权限判断那一份 SQL，正确性只有一处需要保证。
     */
    /**
     * 组装候选：md 静态知识的正文就在 payload 里（不落库），wiki 因为运行期仍以 MySQL 为准，需要回表。
     * <p>
     * 为什么 md 可以不回表：md 是随应用一起发布的静态源，检索侧拿到的就是"发布时的版本"，
     * 不存在"库与源不一致"的可能；而 wiki 是 Agent 运行期写的，正文必须回 MySQL 取最新值。
     * <p>
     * 可见性：md 条目全部是全局可见（{@code scope=GLOBAL}），不需要按用户/主机过滤；
     * wiki 的归属过滤仍然放在回表 SQL 里——越权判断只有一处，错了能被现有测试兜住。
     */
    private List<Candidate> hydrate(String userId, String hostKey, List<VectorStore.Hit> raw) {
        List<Long> wikiIds = new ArrayList<>();
        for (VectorStore.Hit hit : raw) {
            if ("wiki".equals(payloadString(hit.payload(), "source"))) {
                Long id = payloadId(hit.payload());
                if (id != null) {
                    wikiIds.add(id);
                }
            }
        }
        Map<Long, WikiEntryEntity> wikiById = new LinkedHashMap<>();
        for (WikiEntryEntity entity : safe(wikiRepository.queryByIds(cap(wikiIds), userId, hostKey))) {
            wikiById.put(entity.getId(), entity);
        }
        List<Candidate> candidates = new ArrayList<>(raw.size());
        for (VectorStore.Hit hit : raw) {
            Map<String, Object> payload = hit.payload();
            if ("wiki".equals(payloadString(payload, "source"))) {
                Long id = payloadId(payload);
                WikiEntryEntity entity = id == null ? null : wikiById.get(id);
                if (entity == null) {
                    continue;
                }
                candidates.add(new Candidate("w:" + id, withScore(toHit(entity), hit)));
                continue;
            }
            String docId = payloadString(payload, "doc_id");
            String content = payloadString(payload, "content");
            if (docId.isBlank() || content.isBlank()) {
                continue;
            }
            Hit mdHit = new Hit(docId, payloadString(payload, "title"), snippet(content),
                    payloadString(payload, "commands"), payloadString(payload, "tags"),
                    payloadString(payload, "kind"), "md", 0d);
            candidates.add(new Candidate("m:" + docId, withScore(mdHit, hit)));
        }
        return candidates;
    }

    private static List<Long> cap(List<Long> ids) {
        return ids.size() > MAX_LOOKUP ? new ArrayList<>(ids.subList(0, MAX_LOOKUP)) : ids;
    }

    private static <T> List<T> safe(List<T> value) {
        return value == null ? List.of() : value;
    }

    private static Hit withScore(Hit hit, VectorStore.Hit scored) {
        double score = scored == null ? 0d : scored.score();
        return new Hit(hit.docId(), hit.title(), hit.snippet(), hit.commands(),
                hit.tags(), hit.kind(), hit.source(), score);
    }

    /**
     * 两层重排：先本地词面覆盖率（廉价、确定），再模型交叉编码器（可选、有界）。
     * <p>
     * 顺序不能反：本地层是"确定性修正"，模型层只负责它擅长的语义相关性判断；
     * 把本地层放后面就会把模型的分数量纲覆盖掉，而模型分和融合分本来就不可比。
     * <p>
     * 任何一层失效都退回上一层的结果，最差也是召回分数排序——重排不能成为可用性故障点。
     */
    private List<Hit> refine(String query, List<Hit> hits, List<String> terms, int finalLimit) {
        List<Hit> local = localRerank(hits, terms);
        RerankService rerankService = rerankServiceProvider == null ? null : rerankServiceProvider.getIfAvailable();
        if (rerankService == null || !rerankService.available() || local.size() < 2) {
            return truncate(local, finalLimit);
        }
        try {
            List<Hit> refined = rerankService.rerank(query, local, finalLimit);
            // 空列表按"重排失败"处理：不能把"模型没返回"误解成"没有命中"
            return truncate(refined == null || refined.isEmpty() ? local : refined, finalLimit);
        } catch (Exception e) {
            log.warn("[knowledge] 模型重排失败，保留本地重排顺序：{}", e.getMessage());
            return truncate(local, finalLimit);
        }
    }

    private static List<Hit> truncate(List<Hit> hits, int limit) {
        if (hits == null) {
            return List.of();
        }
        return hits.size() > limit ? new ArrayList<>(hits.subList(0, limit)) : hits;
    }

    /**
     * 词面覆盖率重排：融合分只看名次，同一名次的两条命中的"字面贴合度"可能差很多。
     * 覆盖率作为乘性因子（0.5~1.0）最多翻转相邻一档，不会压过名次本身。
     * 纯本地计算、不外呼；模型 rerank 在 {@code RerankService} 里单独一层。
     */
    private static List<Hit> localRerank(List<Hit> hits, List<String> terms) {
        if (hits.size() < 2 || terms == null || terms.isEmpty()) {
            return hits;
        }
        List<Hit> rescored = new ArrayList<>(hits.size());
        for (Hit hit : hits) {
            double factor = 0.5 + 0.5 * coverage(hit, terms);
            rescored.add(new Hit(hit.docId(), hit.title(), hit.snippet(), hit.commands(),
                    hit.tags(), hit.kind(), hit.source(), hit.score() * factor));
        }
        rescored.sort(Comparator.comparingDouble(Hit::score).reversed());
        return rescored;
    }

    /** 查询词（含 2-gram 切片）在标题/命令/摘要中的覆盖率，0~1。 */
    private static double coverage(Hit hit, List<String> terms) {
        String haystack = (nullToEmpty(hit.title()) + " " + nullToEmpty(hit.commands())
                + " " + nullToEmpty(hit.snippet())).toLowerCase();
        int considered = 0;
        int matched = 0;
        for (String term : terms) {
            if (term == null || term.length() < 2) {
                continue;
            }
            considered++;
            if (haystack.contains(term.toLowerCase())) {
                matched++;
            }
        }
        return considered == 0 ? 0d : (double) matched / considered;
    }

    /**
     * 合并两轮结果：按 docId 去重，首轮顺序优先（首轮是用户原话，语义上更贴题）。
     * 用 {@code LinkedHashMap} 而不是 Set：需要保序，且要能按 limit 截断。
     */
    private static List<Hit> merge(List<Hit> first, List<Hit> second, int limit) {
        Map<String, Hit> merged = new LinkedHashMap<>();
        for (Hit hit : first) {
            merged.putIfAbsent(hit.docId(), hit);
        }
        for (Hit hit : second) {
            merged.putIfAbsent(hit.docId(), hit);
        }
        List<Hit> result = new ArrayList<>(merged.values());
        return result.size() > limit ? new ArrayList<>(result.subList(0, limit)) : result;
    }

    private static Long payloadId(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object value = payload.get("id");
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.valueOf(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String payloadString(Map<String, Object> payload, String key) {
        if (payload == null) {
            return "";
        }
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private Hit toHit(WikiEntryEntity entity) {
        // wiki 没有业务编号，用带前缀的主键（与 Candidate 的 id 口径一致），避免与 KB-xxx 混为一谈
        return new Hit("wiki:" + entity.getId(), entity.getTitle(),
                snippet(entity.getContent()), null, entity.getTags(),
                "wiki", entity.getSource(), 0d);
    }

    private String snippet(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        int max = ragProperties.getRetrieval().getSnippetChars();
        String folded = content.replaceAll("\\s+", " ").trim();
        return folded.length() <= max ? folded : folded.substring(0, max) + "…";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 一路召回里的一个候选：id 带来源前缀，保证权威知识和 wiki 的 id 撞号时不会被当成同一条。 */
    private record Candidate(String id, Hit hit) {
    }

    /** 一轮召回的结果：hits 为空时，degraded 用来区分"没有相关知识"与"知识库不可用"。 */
    private record RetrievalRound(List<Hit> hits, boolean degraded) {
    }
}