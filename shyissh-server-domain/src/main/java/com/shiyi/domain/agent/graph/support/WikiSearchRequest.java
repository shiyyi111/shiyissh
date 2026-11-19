package com.shiyi.domain.agent.graph.support;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * wikiSearch 工具入参。
 *
 * @param query 想找什么（自然语言或关键词）
 * @param limit 条数上限，可省略
 * @author shiyi
 */
public record WikiSearchRequest(
        @JsonPropertyDescription("检索关键词，例如「nginx 502」「磁盘满」「端口占用」")
        String query,
        @JsonPropertyDescription("最多返回几条，默认 5，最大 10")
        Integer limit) {
}