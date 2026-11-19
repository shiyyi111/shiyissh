package com.shiyi.domain.agent.service.context.provider.impl;

import com.shiyi.domain.agent.model.valobj.enums.ContextPlacement;
import com.shiyi.domain.agent.service.chat.SummaryVectorService;
import com.shiyi.domain.agent.service.context.provider.ContextProvider;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨会话回忆提供者（order=27）。
 *
 * <p>解决的问题：用户在<b>新会话</b>里问"上次那台机器 502 是怎么解决的"，
 * 而当前会话的上下文里没有任何上一轮的依据——只有摘要表里存着上次的结论。</p>
 *
 * <p>为什么是"摘要向量"而不是"原文向量"：</p>
 * <ul>
 *   <li>原文体积大（每条消息都要切、都要嵌入），且会把窗口重新撑满，等于把历史搬回来；</li>
 *   <li>用户要的是结论（"根因是上游连接池打满，已扩容"），摘要正是结论的载体；</li>
 *   <li>需要明细时，模型可以按 {@code session_id} 调工具回查，不需要提前塞进来。</li>
 * </ul>
 *
 * <p>放置位置：动态尾部（EPHEMERAL_SUFFIX）。它随本轮问题变化，放进稳定前缀会击穿前缀缓存。</p>
 *
 * <p>输出字段 {@code sessionRecallSummary}，由 {@code DynamicPromptBuilder} 渲染为 [历史会话回忆] 段落。</p>
 *
 * @author shiyi
 */
@Component
public class SessionRecallProvider implements ContextProvider {

    /** 最多带 2 条：跨会话回忆是"补充证据"，多了会喧宾夺主。 */
    private static final int RECALL_LIMIT = 2;

    /**
     * 低于这个融合分的不带。
     * <p>
     * rrf 融合分不是概率，量级取决于名次与融合常数：同一批结果内部比较有意义，跨请求比较没有意义。
     * 这里取一个「明显无关」的下限而不是 0：0 等于不过滤，任何一条召回都会被塞进上下文。
     */
    private static final double MIN_SCORE = 0.01;

    @Resource
    private SummaryVectorService summaryVectorService;

    @Override
    public String getName() {
        return "session-recall";
    }

    /**
     * 排在长期记忆的动态召回（26）之后：先给"经验"，再给"别处发生过的事"。
     */
    @Override
    public int getOrder() {
        return 27;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public ContextPlacement getPlacement() {
        return ContextPlacement.EPHEMERAL_SUFFIX;
    }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId,
                                       List<Map<String, Object>> messageHistory) {
        return provide(sessionId, userId, terminalSessionId, "", messageHistory);
    }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId,
                                       String hostKey, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();
        if (summaryVectorService == null || userId == null || userId.isBlank()) {
            return result;
        }
        String query = latestUserMessage(messageHistory);
        if (query.isBlank()) {
            return result;
        }
        List<SummaryVectorService.SummaryRecall> recalls =
                summaryVectorService.recall(userId, sessionId, query, RECALL_LIMIT);
        if (recalls == null || recalls.isEmpty()) {
            return result;
        }
        StringBuilder sb = new StringBuilder();
        for (SummaryVectorService.SummaryRecall recall : recalls) {
            if (recall.score() < MIN_SCORE) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            // 明确写"这是另一次会话的结论"，避免模型把它当成当前会话的事实
            sb.append("- （另一次会话）").append(recall.summary());
        }
        if (sb.length() > 0) {
            result.put("sessionRecallSummary", sb.toString());
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
        Collections.reverse(reversed);
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