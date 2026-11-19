package com.shiyi.domain.agent.graph.support;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * saveWiki 工具入参：把一次排查的结论沉淀成可检索的知识。
 *
 * @param title   一句话标题，检索时直接展示（例如「nginx 502 是 upstream 超时」）
 * @param content 结论正文，要脱离本次对话也能看懂
 * @param tags    逗号分隔标签，可省略
 * @author shiyi
 */
public record SaveWikiRequest(
        @JsonPropertyDescription("一句话标题，写清「什么现象 → 什么原因」，便于以后按现象检索")
        String title,
        @JsonPropertyDescription("结论正文：现象、根因、处理动作、验证方式，不要贴命令输出原文")
        String content,
        @JsonPropertyDescription("逗号分隔的标签，例如 nginx,502,超时")
        String tags) {
}