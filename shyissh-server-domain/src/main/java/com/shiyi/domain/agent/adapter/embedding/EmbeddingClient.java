package com.shiyi.domain.agent.adapter.embedding;

import java.util.List;

/**
 * 文本向量化端口。
 * <p>
 * 只做一件事：把一段文本变成固定维度的向量。上层（检索、记忆召回）不关心用的是哪家模型、
 * 走的是 OpenAI 兼容协议还是原生 SDK，换模型只改实现类与配置。
 * <p>
 * 为什么单独抽一个端口而不是直接在检索服务里 {@code new OpenAiApi(...)}：
 * <ul>
 *   <li><b>可降级</b>：模型网关抖动、额度用尽、配置写错时，{@link #available()} 返回 false，
 *       检索链路自动退回纯关键词，而不是整个问答报错；</li>
 *   <li><b>可替换</b>：本地 Ollama、别的厂商、甚至测试里的确定性假实现，都只需实现这个接口；</li>
 *   <li><b>维度是契约</b>：{@link #dimensions()} 决定向量库 collection 的维度，
 *       模型与维度必须成对更换，否则向量库会拒绝写入。</li>
 * </ul>
 *
 * @author shiyi
 */
public interface EmbeddingClient {

    /** 当前是否可用（配置齐全且最近一次调用没有失败）。不可用时调用方应降级为纯关键词检索。 */
    boolean available();

    /** 向量维度；用于创建 collection 与校验写入。 */
    int dimensions();

    /** 模型名；用于写入 payload 做溯源，换模型后能区分新旧向量。 */
    String model();

    /**
     * 单条文本向量化。
     *
     * @param text 待向量化文本，调用方负责截断到模型上限内
     * @return 定长向量；失败返回 {@code null}（调用方据此降级，不抛异常中断主流程）
     */
    float[] embed(String text);

    /**
     * 批量向量化。
     *
     * @param texts 文本列表，按顺序返回
     * @return 与入参等长的向量列表；任一条失败返回 {@code null}，避免"一半有结果一半没有"的脏状态
     */
    List<float[]> embedBatch(List<String> texts);
}