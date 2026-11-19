package com.shiyi.domain.agent.graph.support;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 模型主动召回记忆时的入参。
 * <p>
 * 为什么需要这个工具：长期记忆平时是"自动注入"的——入口按首条用户消息拼一次摘要，
 * 之后每轮再拼最相关的几条。但注入的查询词是固定的，模型如果突然想确认
 * "上次这台机器磁盘满是怎么处理的"，只能靠自己提问触发一次精准召回。
 * 把这个动作做成工具，模型就能在自己的推理中途补一次信息，而不是被迫在答案里猜。
 *
 * @param query 想找什么，用自然语言描述，例如"磁盘写满 处理方式"
 * @param kind  只看某一类记忆：PREFERENCE / ENVIRONMENT / SOFTWARE / CASE，留空则不限
 * @author shiyi
 */
public record RecallMemoryRequest(
        @JsonPropertyDescription("要检索的记忆内容，用自然语言描述，例如：磁盘写满、nginx 安装路径、之前的排查结论")
        String query,
        @JsonPropertyDescription("只看某一类记忆，可选 PREFERENCE / ENVIRONMENT / SOFTWARE / CASE，留空表示不限类型")
        String kind) {
}