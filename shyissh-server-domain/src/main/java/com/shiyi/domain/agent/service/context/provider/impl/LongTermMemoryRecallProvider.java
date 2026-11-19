package com.shiyi.domain.agent.service.context.provider.impl;

import com.shiyi.domain.agent.model.valobj.enums.ContextPlacement;
import com.shiyi.domain.agent.service.ILongTermMemoryService;
import com.shiyi.domain.agent.service.context.provider.ContextProvider;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆的"本轮相关召回"提供者（order=26）。
 *
 * <p>与 {@link LongTermMemoryProvider} 的分工：</p>
 * <ul>
 *   <li>{@code LongTermMemoryProvider}（STABLE_PREFIX）——只用会话首条用户消息检索，
 *       同一会话内召回结果不变，因此可以安全地进入稳定前缀，前缀缓存不会被击穿；</li>
 *   <li>{@code LongTermMemoryRecallProvider}（EPHEMERAL_SUFFIX）——用最新一轮用户输入检索，
 *       补充"当前这句话"相关的历史经验，作为动态尾部内容，即使每轮变化也不影响缓存。</li>
 * </ul>
 *
 * <p>输出字段 {@code longTermRecallSummary}，由 {@code DynamicPromptBuilder} 渲染为 [相关记忆] 段落。</p>
 *
 * @see LongTermMemoryProvider
 */
@Component
public class LongTermMemoryRecallProvider implements ContextProvider {

    /** 动态召回条数上限；比稳定区更少，避免尾部膨胀。 */
    private static final int RECALL_LIMIT = 3;

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    @Override
    public String getName() {
        return "long-term-recall";
    }

    /**
     * 排序 26，紧跟在稳定区的 LongTermMemoryProvider(25) 之后。
     */
    @Override
    public int getOrder() {
        return 26;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    /**
     * 明确声明为动态尾部：内容每轮随用户输入变化，放进稳定前缀会破坏前缀缓存。
     */
    @Override
    public ContextPlacement getPlacement() {
        return ContextPlacement.EPHEMERAL_SUFFIX;
    }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId,
                                       List<Map<String, Object>> messageHistory) {
        return provide(sessionId, userId, terminalSessionId, "", messageHistory);
    }

    /**
     * 按当前输入召回跨会话记忆（带主机标识）。
     * <p>
     * 与稳定前缀里的长期记忆摘要互补：那里放"整条会话不变"的偏好类记忆，
     * 这里放"与这句话最相关"的记忆，因此每轮都可能不同。
     * 主机标识用来把本机的环境事实限定在本机。
     */
    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId,
                                       String hostKey, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();
        String query = latestUserMessage(messageHistory);
        if (query.isBlank()) {
            return result;
        }

        String summary = longTermMemoryService.buildMemorySummary(userId, hostKey, query, RECALL_LIMIT);
        if (summary != null && !summary.isBlank()) {
            result.put("longTermRecallSummary", summary);
        }
        return result;
    }

    /**
     * 取最近一条用户消息作为召回查询。
     */
    private String latestUserMessage(List<Map<String, Object>> messageHistory) {
        if (messageHistory == null || messageHistory.isEmpty()) {
            return "";
        }
        List<Map<String, Object>> reversed = new ArrayList<>(messageHistory);
        java.util.Collections.reverse(reversed);
        for (Map<String, Object> message : reversed) {
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