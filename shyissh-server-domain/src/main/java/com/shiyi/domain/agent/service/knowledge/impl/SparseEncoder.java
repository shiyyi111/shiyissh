package com.shiyi.domain.agent.service.knowledge.impl;

import com.shiyi.domain.agent.adapter.vector.SparseVector;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文友好的稀疏编码器：把文本切成词项并算出稳定的 uint32 下标 + 权重。
 * <p>
 * <b>为什么是 2-gram 而不是分词</b>：
 * <ul>
 *   <li>不引依赖：jieba/HanLP 要么带词典要么带统计模型，体积和启动成本都不适合一个"检索增强"模块；</li>
 *   <li>无未登录词问题：中文的 2 字滑窗对"磁盘 inod"这类混杂查询同样有效，而词典分词会把新词切成碎片；</li>
 *   <li>召回优先：稀疏路的目标是"精确串不漏"，2-gram 的过度切分换来的是召回，排序交给 BM25 的 IDF 与后续融合。</li>
 * </ul>
 * 英文/数字按词切分后整词入袋（{@code nginx}、{@code 502}、{@code df}），并与中文 2-gram 共用一张哈希表——
 * Qdrant 的 sparse 索引不关心词项来自哪种语言，只关心"同一段文本两次编码结果一致"。
 * <p>
 * 权重用 sublinear tf（{@code 1 + ln(tf)}）：一个词出现 10 次不该比出现 1 次重要 10 倍。
 * IDF 不在这里算——集合上是 {@code modifier: idf}，由 Qdrant 按全库文档频次现算，
 * 客户端只送词频，这样新增文档不需要重建历史向量。
 *
 * @author shiyi
 */
public final class SparseEncoder {

    /** FNV-1a 的 32 位偏移基与质数（与 Qdrant 的 uint32 下标范围对齐）。 */
    private static final int FNV_OFFSET = 0x811C9DC5;
    private static final int FNV_PRIME = 0x01000193;

    /** 单次编码最多保留的词项数：payload 体积与检索延迟都有上界，超出按词频截断。 */
    private static final int MAX_TERMS = 512;

    private SparseEncoder() {
    }

    /**
     * 编码一段文本。
     *
     * @param text 原文；null/空返回空向量
     * @return 下标升序、权重为 sublinear tf 的稀疏向量
     */
    public static SparseVector encode(String text) {
        if (text == null || text.isBlank()) {
            return SparseVector.empty();
        }
        Map<Long, Integer> counts = new HashMap<>(64);
        collect(text, counts);
        if (counts.isEmpty()) {
            return SparseVector.empty();
        }
        List<Map.Entry<Long, Integer>> entries = new ArrayList<>(counts.entrySet());
        if (entries.size() > MAX_TERMS) {
            // 只在超限时排序截断：高频词项更能代表这段文本
            entries.sort(Map.Entry.<Long, Integer>comparingByValue().reversed());
            entries = entries.subList(0, MAX_TERMS);
            entries = new ArrayList<>(entries);
        }
        entries.sort(Map.Entry.comparingByKey());
        long[] indices = new long[entries.size()];
        float[] values = new float[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            indices[i] = entries.get(i).getKey();
            values[i] = (float) (1.0 + Math.log(entries.get(i).getValue()));
        }
        return new SparseVector(indices, values);
    }

    /**
     * 编码查询串。与文档编码同构（查询也要能被同一批词项命中），
     * 只是不做超限排序截断——查询通常很短，截断反而丢性命中词。
     */
    public static SparseVector encodeQuery(String query) {
        if (query == null || query.isBlank()) {
            return SparseVector.empty();
        }
        Map<Long, Integer> counts = new HashMap<>(32);
        collect(query, counts);
        if (counts.isEmpty()) {
            return SparseVector.empty();
        }
        List<Map.Entry<Long, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        long[] indices = new long[entries.size()];
        float[] values = new float[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            indices[i] = entries.get(i).getKey();
            values[i] = (float) (1.0 + Math.log(entries.get(i).getValue()));
        }
        return new SparseVector(indices, values);
    }

    private static void collect(String text, Map<Long, Integer> counts) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase();
        int length = normalized.length();
        int i = 0;
        while (i < length) {
            int cp = normalized.codePointAt(i);
            if (isCjk(cp)) {
                // 连续 CJK 段：段内取相邻 2 字（单字段则取该字本身）
                int start = i;
                while (i < length) {
                    int next = normalized.codePointAt(i);
                    if (!isCjk(next)) {
                        break;
                    }
                    i += Character.charCount(next);
                }
                String run = normalized.substring(start, i);
                int[] points = run.codePoints().toArray();
                if (points.length == 1) {
                    add(counts, new String(points, 0, 1));
                } else {
                    for (int k = 0; k + 1 < points.length; k++) {
                        add(counts, new String(points, k, 2));
                    }
                }
                continue;
            }
            if (Character.isLetterOrDigit(cp)) {
                int start = i;
                while (i < length) {
                    int next = normalized.codePointAt(i);
                    if (isCjk(next) || !Character.isLetterOrDigit(next)) {
                        break;
                    }
                    i += Character.charCount(next);
                }
                add(counts, normalized.substring(start, i));
                continue;
            }
            // 标点/空白/符号：跳过
            i += Character.charCount(cp);
        }
    }

    private static void add(Map<Long, Integer> counts, String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        long hash = fnv1a(token) & 0xFFFFFFFFL;
        counts.merge(hash, 1, Integer::sum);
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)      // 基本汉字
                || (cp >= 0x3400 && cp <= 0x4DBF)  // 扩展 A
                || (cp >= 0xF900 && cp <= 0xFAFF)  // 兼容汉字
                || (cp >= 0x3040 && cp <= 0x30FF); // 假名（混排时按 CJK 处理）
    }

    private static int fnv1a(String token) {
        int hash = FNV_OFFSET;
        for (int i = 0; i < token.length(); i++) {
            hash ^= token.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    /**
     * 调试/测试辅助：把文本切成词项列表（不哈希），用于断言"切分符合预期"。
     */
    static List<String> tokens(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase();
        int length = normalized.length();
        int i = 0;
        while (i < length) {
            int cp = normalized.codePointAt(i);
            if (isCjk(cp)) {
                int start = i;
                while (i < length) {
                    int next = normalized.codePointAt(i);
                    if (!isCjk(next)) {
                        break;
                    }
                    i += Character.charCount(next);
                }
                int[] points = normalized.substring(start, i).codePoints().toArray();
                if (points.length == 1) {
                    result.add(new String(points, 0, 1));
                } else {
                    for (int k = 0; k + 1 < points.length; k++) {
                        result.add(new String(points, k, 2));
                    }
                }
                continue;
            }
            if (Character.isLetterOrDigit(cp)) {
                int start = i;
                while (i < length) {
                    int next = normalized.codePointAt(i);
                    if (isCjk(next) || !Character.isLetterOrDigit(next)) {
                        break;
                    }
                    i += Character.charCount(next);
                }
                result.add(normalized.substring(start, i));
                continue;
            }
            i += Character.charCount(cp);
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }
}