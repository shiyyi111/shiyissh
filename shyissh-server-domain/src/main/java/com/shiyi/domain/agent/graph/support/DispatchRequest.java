package com.shiyi.domain.agent.graph.support;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 派发子智能体的工具入参。
 * <p>
 * 字段上的 {@code @JsonPropertyDescription} 会进入 tool schema，
 * 是模型判断"该填谁、填什么"的唯一依据，所以写具体一点。
 *
 * @author shiyi
 */
public record DispatchRequest(
        @JsonPropertyDescription("要派发的子智能体名称，必须从工具描述里列出的可用子智能体中选择")
        String agent,
        @JsonPropertyDescription("交给该子智能体完成的具体任务，写清楚要查什么、要得出什么结论")
        String task) {
}