package com.shiyi.domain.agent.graph.support;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * 批量派发工具的入参。
 * <p>
 * 字段说明会作为 JSON Schema 的 description 交给模型，所以措辞要具体——
 * 模型完全靠这段文字决定"要不要写 dependsOn、maxConcurrency 填几"。
 *
 * @author shiyi
 */
public record BatchDispatchRequest(

        @JsonPropertyDescription("要派发的子任务列表，彼此无依赖的任务会被并行执行；任务之间存在先后依赖时必须用 dependsOn 声明")
        List<TaskSpec> tasks,

        @JsonPropertyDescription("并行执行的最大任务数，默认 4。涉及写操作（变更类命令）时建议填 1，避免多个子智能体同时改动同一台机器")
        Integer maxConcurrency,

        @JsonPropertyDescription("某个任务失败后是否立即停止派发剩余任务，默认 false。true 适合必须按顺序成功的场景")
        Boolean failFast) {

    /**
     * 单个子任务。
     *
     * @param taskId    任务唯一标识，可省略（会自动生成 task-1、task-2），但被 dependsOn 引用时必须显式给
     * @param agentName 子智能体名称，必须从工具描述里列出的可用子智能体中选择
     * @param request   交给该子智能体完成的具体任务，写清楚要查什么、要得出什么结论
     * @param dependsOn 依赖的前置任务 ID 列表；为空表示可以立即执行
     */
    public record TaskSpec(
            @JsonPropertyDescription("任务唯一标识，例如 check-disk；会被依赖该任务的其他任务通过 dependsOn 引用")
            String taskId,

            @JsonPropertyDescription("要派发的子智能体名称，必须从可用子智能体列表中选择")
            String agentName,

            @JsonPropertyDescription("交给该子智能体完成的具体任务，写清楚要查什么、要得出什么结论")
            String request,

            @JsonPropertyDescription("依赖的前置任务 ID 列表，这些任务都成功完成后本任务才会被调度；为空表示可立即执行")
            List<String> dependsOn) {
    }
}