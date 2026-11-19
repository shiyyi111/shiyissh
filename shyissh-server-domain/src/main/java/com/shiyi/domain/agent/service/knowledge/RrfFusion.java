package com.shiyi.domain.agent.service.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * RRF（Reciprocal Rank Fusion）融合：把"关键词排序"和"向量排序"两路结果合成一个排名。
 * <p>
 * 公式：{@code score(d) = Σ 1 / (k + rank_i(d))}，{@code k} 默认 60（原论文经验值）。
 * <p>
 * 为什么用 RRF 而不是加权分数相加：
 * <ul>
 *   <li>两路分数不可比——FULLTEXT 的 BM25 值可以是 0.x 也可以是 30+，余弦相似度固定在 [-1,1]，
 *       直接加权需要先做归一化，而归一化的方式本身就会引入调参；</li>
 *   <li>RRF 只看名次，天然免疫量纲差异；</li>
 *   <li>两路都排前面的条目得分最高，只有一路排第一的条目也会得到不错的分数——
 *       这正是"精确词命中"与"语义命中"互补想要的效果。</li>
 * </ul>
 *
 * @author shiyi
 */
public final class RrfFusion {

    /** 论文默认值：k 越大，名次差异被抹得越平，长尾条目越容易冒头。 */
    public static final int DEFAULT_K = 60;

    private RrfFusion() {
    }

    /**
     * 融合多路有序结果。
     *
     * @param rankedLists 每路已按相关性降序的结果；允许为空列表或 null
     * @param k           平滑常数，一般用 {@link #DEFAULT_K}
     * @param idOf        从元素取出唯一标识（用于跨路去重）
     * @param <T>         元素类型
     * @return 融合后的结果，按得分降序；同分时"最好名次更靠前"的优先
     */
    public static <T> List<Scored<T>> fuse(List<List<T>> rankedLists, int k, Function<T, String> idOf) {
        if (rankedLists == null || rankedLists.isEmpty() || idOf == null) {
            return List.of();
        }
        int effectiveK = k <= 0 ? DEFAULT_K : k;
        Map<String, Accumulator<T>> acc = new LinkedHashMap<>();
        for (List<T> list : rankedLists) {
            if (list == null || list.isEmpty()) {
                continue;
            }
            int rank = 0;
            for (T item : list) {
                if (item == null) {
                    continue;
                }
                rank++;
                String id = idOf.apply(item);
                if (id == null) {
                    continue;
                }
                Accumulator<T> a = acc.computeIfAbsent(id, key -> new Accumulator<>(item));
                a.score += 1.0d / (effectiveK + rank);
                a.bestRank = Math.min(a.bestRank, rank);
                a.ranks.add(rank);
            }
        }
        List<Scored<T>> result = new ArrayList<>(acc.size());
        for (Accumulator<T> a : acc.values()) {
            result.add(new Scored<>(a.item, a.score, a.bestRank, List.copyOf(a.ranks)));
        }
        result.sort((x, y) -> {
            int byScore = Double.compare(y.score(), x.score());
            if (byScore != 0) {
                return byScore;
            }
            return Integer.compare(x.bestRank(), y.bestRank());
        });
        return result;
    }

    private static final class Accumulator<T> {
        private final T item;
        private double score;
        private int bestRank = Integer.MAX_VALUE;
        private final List<Integer> ranks = new ArrayList<>();

        private Accumulator(T item) {
            this.item = item;
        }
    }

    /**
     * 融合结果。
     *
     * @param item     原始元素
     * @param score    RRF 得分
     * @param bestRank 在各路中的最好名次（1 起）
     * @param ranks    在各路中的名次列表，用于调试"为什么它被排上来"
     */
    public record Scored<T>(T item, double score, int bestRank, List<Integer> ranks) {
    }
}