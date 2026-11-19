package com.shiyi.domain.agent.service.knowledge;

import java.util.List;

/**
 * 知识检索端口（关键词 + 向量混合）。
 * <p>
 * 上层（{@code GraphMemoryToolService} / 未来的 {@code askKnowledgeBase} 工具）只依赖这个接口，
 * 不关心底下是 FULLTEXT、Qdrant，还是两者融合。
 * <p>
 * 返回的每条命中都带 {@code docId} 与 {@code source}：模型回答时要能写"按知识库 [KB-001]"，
 * 用户才能回溯到具体条目；同时区分"权威条目"与"Agent 沉淀"的可信度差异。
 *
 * @author shiyi
 */
public interface KnowledgeRetriever {

    /**
     * 检索。
     *
     * @param userId  当前用户
     * @param hostKey 当前主机；空串表示通用条目
     * @param query   用户原话（内部会归一化）
     * @param limit   返回条数上限
     * @return 检索结果；任何异常都应降级为空结果而不是抛出
     */
    Result retrieve(String userId, String hostKey, String query, int limit);

    /**
     * 一条命中。
     *
     * @param docId    条目编号（如 {@code KB-001}）或 wiki 主键（如 {@code wiki:12}）
     * @param title    标题
     * @param snippet  截断后的正文，供模型判断相关性
     * @param commands 条目里的命令块（可为空串）；运维场景命令比正文更有用
     * @param tags     标签
     * @param kind     runbook / policy / incident / wiki
     * @param source   manual / agent / import
     * @param score    融合得分（RRF，越大越相关）
     */
    record Hit(String docId, String title, String snippet, String commands,
               String tags, String kind, String source, double score) {
    }

    /**
     * 检索结果。
     *
     * @param hits            命中列表，已按融合得分降序
     * @param normalizedQuery 归一化后的检索词（用于日志与前端展示"实际搜了什么"）
     * @param rewritten       是否触发了 LLM 改写
     * @param elapsedMs       检索耗时
     * @param degraded        是否降级：向量库不可用/检索失败时为 true，此时 hits 为空且语义是
     *                        "知识库暂时不可用"，调用方应如实告诉模型"这次没查到"而不是"没有相关知识"
     */
    record Result(List<Hit> hits, String normalizedQuery, boolean rewritten, long elapsedMs, boolean degraded) {

        /** 未降级的普通结果。 */
        public static Result of(List<Hit> hits, String normalizedQuery, boolean rewritten, long elapsedMs) {
            return new Result(hits, normalizedQuery, rewritten, elapsedMs, false);
        }

        public boolean isEmpty() {
            return hits == null || hits.isEmpty();
        }
    }
}