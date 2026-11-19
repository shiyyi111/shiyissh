package com.shiyi.domain.agent.service.knowledge;

import java.util.List;

/**
 * 结果重排端口（rerank）。
 * <p>
 * 为什么需要它：混合召回的分数量纲是"两路名次的融合"，它回答的是
 * "这条更容易被找到吗"，而不是"这条真的回答了问题吗"。交叉编码器把
 * (query, 候选正文) 拼在一起逐对打分，能把"词面命中但答非所问"的条目压下去。
 * <p>
 * 约束（R13 有界）：
 * <ul>
 *   <li>只允许<strong>知识检索</strong>调用。记忆召回每轮跑两次（stable/ephemeral 各一次），
 *       一次请求最多 6 次，若也接 rerank 就是 6 次外呼——那是把"排序优化"变成了"可用性依赖"；</li>
 *   <li>实现必须<strong>失败即回退</strong>：超时、限流、返回格式不符，一律返回入参原顺序，
 *       绝不能抛异常给上层，也不能返回空列表（否则"重排失败"会被误解成"没有命中"）；</li>
 *   <li>实现必须自己<strong>限制条数</strong>（{@code app.rag.rerank.max-docs}），
 *       调用方只保证传入的已经是有序候选。</li>
 * </ul>
 *
 * @author shiyi
 */
public interface RerankService {

    /** 当前是否可以重排（配置开关 + key 是否可用）；false 时调用方应直接跳过。 */
    boolean available();

    /**
     * 对候选重排。
     *
     * @param query 用户原始查询（已归一化，不含同义词展开）
     * @param hits  召回结果，按现有分数从高到低；实现只能重排、不能新增
     * @param topN  期望返回条数；实现返回的列表长度不得超过它
     * @return 重排后的结果；实现无法重排时原样返回 hits（可能长于 topN，由调用方截断）
     */
    List<KnowledgeRetriever.Hit> rerank(String query, List<KnowledgeRetriever.Hit> hits, int topN);
}