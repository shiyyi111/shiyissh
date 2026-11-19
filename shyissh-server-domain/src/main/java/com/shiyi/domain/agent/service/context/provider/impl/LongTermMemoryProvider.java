package com.shiyi.domain.agent.service.context.provider.impl;

import com.shiyi.domain.agent.model.valobj.enums.ContextPlacement;
import com.shiyi.domain.agent.service.ILongTermMemoryService;
import com.shiyi.domain.agent.service.context.provider.ContextProvider;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆上下文提供者（order=25）
 * <p>
 * 在 ReAct 循环构建 Prompt 时，自动召回与当前对话相关的长期记忆，
 * 输出 {@code longTermMemorySummary} 字段，经 DynamicPromptBuilder 渲染为 [长期记忆] 段落。
 * <p>
 * 排序 order=25，位于 TaskProvider(20) 之后、MilestoneProvider(30) 之前，
 * 表达"先确定任务、再召回记忆、最后收集里程碑"的语义优先级。
 * <p>
 * 召回查询构造策略：只取会话**首条**用户消息（初始目标）。
 * <p>
 * 为什么不用"首条 + 最近"拼接：本 Provider 的输出去 STABLE_PREFIX（稳定前缀），
 * 若查询随轮次变化，召回结果就会变化，稳定前缀逐轮变化，服务端前缀缓存永远命中
 * （这是在世界 2-9 里验证过的真实坑）。因此这里只用首条，让召回内容在会话内基本稳定。
 *
 * @see ContextProvider
 * @see com.shiyi.domain.agent.service.memory.LongTermMemoryService#buildMemorySummary
 */
@Component
public class LongTermMemoryProvider implements ContextProvider {

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    @Override
    public String getName() {
        return "long-term-memory";
    }

    /**
     * 排序权重 25，位于 TaskProvider(20) 与 MilestoneProvider(30) 之间
     */
    @Override
    public int getOrder() {
        return 25;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    /**
     * 长期记忆放在稳定前缀：会话内基本不变，能帮模型保持任务目标。
     */
    @Override
    public ContextPlacement getPlacement() {
        return ContextPlacement.STABLE_PREFIX;
    }

    /**
     * 召回长期记忆并构建摘要，注入 PromptContextVO.longTermMemorySummary。
     *
     * @param sessionId          会话 ID
     * @param userId             用户 ID
     * @param terminalSessionId  终端会话 ID
     * @param messageHistory     消息历史（用于构造召回查询）
     * @return 包含 longTermMemorySummary 的 Map，无记忆时返回空 Map
     */
    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId, List<Map<String, Object>> messageHistory) {
        return provide(sessionId, userId, terminalSessionId, "", messageHistory);
    }

    /**
     * 召回长期记忆（带主机标识）。
     * <p>
     * 查询词仍然只取会话首条用户消息，保证稳定前缀整条会话不变；
     * 主机标识只用来限定 HOST 作用域的记忆，让"这台机器 Redis 是 6.2"这类事实
     * 不会被带到另一台机器上。
     */
    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId,
                                       String hostKey, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();
        String query = buildQuery(messageHistory);
        String summary = longTermMemoryService.buildMemorySummary(userId, hostKey, query, 5);
        if (!summary.isBlank()) {
            result.put("longTermMemorySummary", summary);
        }
        return result;
    }

    /**
     * 构造召回查询：只取会话首条用户消息（会话的初始目标）。
     * <p>
     * 长期记忆摘要放在 STABLE_PREFIX（前缀最前面），按定义必须"整条会话不变"。
     * 若把"首条 + 最近一条"拼接，查询每轮都变，召回的记忆也会每轮不同，
     * 稳定前缀于是逐轮变化，服务端前缀缓存永远命中（世界 2-9 验证过的坑）。
     * <p>
     * 代价：会话中途换话题时，召回偏向前期目标；当前轮次的相关性由动态后缀里的
     * LongTermMemoryRecallProvider 补足（它按当前消息检索，不参与前缀缓存）。
     *
     * @param messageHistory 消息历史
     * @return 召回查询字符串，无用户消息时返回空字符串
     */
    private String buildQuery(List<Map<String, Object>> messageHistory) {
        if (messageHistory == null || messageHistory.isEmpty()) {
            return "";
        }
        for (Map<String, Object> message : messageHistory) {
            if (!"user".equals(message.get("role"))) {
                continue;
            }
            String content = String.valueOf(message.getOrDefault("content", ""));
            if (!content.isBlank()) {
                return content;
            }
        }
        return "";
    }
}
