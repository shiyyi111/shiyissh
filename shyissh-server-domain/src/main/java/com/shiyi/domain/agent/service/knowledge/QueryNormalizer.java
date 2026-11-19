package com.shiyi.domain.agent.service.knowledge;

import java.util.List;

/**
 * 查询归一化端口。
 * <p>
 * 把用户原话变成"更适合检索的检索词"，这一步是<b>确定性的</b>：同样的输入永远得到同样的输出，
 * 因为它只做规则替换与信号抽取，不调用模型。
 * <p>
 * 为什么不用模型做这一步：
 * <ul>
 *   <li>归一化要可复现——同样的问句两次检索必须命中同一批条目，否则没法写回归测试、没法排查；</li>
 *   <li>延迟与失败面——每次问答多一次 300–800ms 的模型调用，还要处理超时、限流、格式错；</li>
 *   <li>术语是人审约定——"prod-db-03"、"订单服务"、"fpm" 这类词表由人维护，模型只能猜。</li>
 * </ul>
 * 真正需要"语义推断"的开放描述（"机器卡得要死"）由 {@code LlmQueryRewriter} 兜底，
 * 且只在关键词召回不足时触发，不在主路径上。
 *
 * @author shiyi
 */
public interface QueryNormalizer {

    /**
     * 归一化。
     *
     * @param rawQuery 用户原话
     * @return 归一化结果；空输入返回空结果而不是 null
     */
    Normalized normalize(String rawQuery);

    /**
     * 归一化结果。
     *
     * @param original      原始查询
     * @param normalized    替换别名、统一大小写与全半角之后的检索词
     * @param terms         用于关键词检索的切词结果（已去停用词、去重）
     * @param strongSignals 强信号：主机名、IP、端口、错误码、已知命令名。
     *                      非空时说明这条查询"字面信息足够"，直接检索、不触发改写
     */
    record Normalized(String original, String normalized, List<String> terms, List<String> strongSignals) {

        public boolean hasStrongSignal() {
            return strongSignals != null && !strongSignals.isEmpty();
        }

        public boolean isEmpty() {
            return normalized == null || normalized.isBlank();
        }
    }
}