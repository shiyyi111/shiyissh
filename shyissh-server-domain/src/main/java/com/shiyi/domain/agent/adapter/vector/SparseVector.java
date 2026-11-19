package com.shiyi.domain.agent.adapter.vector;

import java.util.Objects;

/**
 * 稀疏向量：只存"词项 → 权重"的非零项，用于关键词路（BM25 式打分）。
 * <p>
 * 为什么不用稠密向量做关键词：稠密向量把整段文本压成 1024 个浮点，精确串（{@code nginx 502}、
 * {@code df -h}、错误码）在压缩中会被"同义化"，反而召不准；稀疏向量保留词项身份，
 * 命中就是命中，两侧同时用（Qdrant 一次请求里 prefetch 两路再 RRF 融合）才叫真正的混合检索。
 * <p>
 * {@code indices} 由 {@code SparseEncoder} 用 FNV-1a 哈希得到，Qdrant 侧是 uint32，
 * 所以这里用 {@code long} 承载并保证落在非负 32 位范围内。
 *
 * @param indices 词项下标（升序去重）
 * @param values  对应权重
 */
public record SparseVector(long[] indices, float[] values) {

    public SparseVector {
        Objects.requireNonNull(indices, "indices");
        Objects.requireNonNull(values, "values");
        if (indices.length != values.length) {
            throw new IllegalArgumentException("indices 与 values 长度必须一致");
        }
    }

    /** 空向量：查询词全被过滤掉时用它，调用方据此决定是否只走稠密路。 */
    public static SparseVector empty() {
        return new SparseVector(new long[0], new float[0]);
    }

    public boolean isEmpty() {
        return indices.length == 0;
    }
}