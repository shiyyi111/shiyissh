package com.shiyi.domain.agent.model.valobj.prompt;

import com.shiyi.domain.agent.model.valobj.intent.IntentResultVO;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class PromptContextVO {

    private String serverInfo;
    private String osInfo;
    private String currentUser;
    private String currentDirectory;

    /**
     * 执行命令
     */
    private List<String> recentCommands;

    /**
     * 里程碑记录
     */
    private List<MilestoneVO> milestoneVOS;

    /**
     * 长期记忆摘要（跨会话、结构化召回）
     */
    private String longTermMemorySummary;

    /**
     * 当前任务描述（首条用户消息）
     */
    private String taskDescription;

    /**
     * 当前意图标签（由意图识别系统经 PromptService.buildEnrichedMessage(intentLabel) 注入）。
     * <p>
     * 值为 {@code IntentTypeEnumVO.name()}（如 "DIAGNOSE"），可为 null 表示未识别。
     * 由 {@code DynamicPromptBuilder} 渲染为消息前缀 "[用户意图] xxx"，让主模型感知意图但不强制路由。
     */
    private String intentLabel;

    /**
     * 本轮意图识别结果，渲染为非权威提示（仅辅助，不作为强制指令）。
     */
    private IntentResultVO intentResult;

    /**
     * 稳定前缀上下文（来自 {@code getPlacement() == STABLE_PREFIX} 的 Provider）。
     * <p>
     * 这些内容在会话内低频变化（当前任务、历史沉淀的长期记忆），
     * 放在 Prompt 最前面，让服务端前缀缓存有稳定的命中面。
     */
    @Builder.Default
    private Map<String, Object> stableContext = Map.of();

    /**
     * 动态后缀上下文（来自 {@code getPlacement() == EPHEMERAL_SUFFIX} 的 Provider）。
     * <p>
     * 系统环境、最近命令、关键事件、工具摘要、本轮相关记忆召回等每轮都会变的内容，
     * 统一放到用户消息之后的尾部，避免污染前缀。
     */
    @Builder.Default
    private Map<String, Object> ephemeralContext = Map.of();
}

