package com.shiyi.domain.agent.service.context;

import com.shiyi.domain.agent.model.valobj.prompt.MilestoneVO;
import com.shiyi.domain.agent.model.valobj.enums.ContextPlacement;
import com.shiyi.domain.agent.model.valobj.prompt.PromptContextVO;
import com.shiyi.domain.agent.service.IChatContextService;
import com.shiyi.domain.agent.service.context.provider.ContextProvider;
import com.shiyi.domain.agent.service.context.reducer.impl.HybridReducer;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文管理领域服务（context 包的聚合核心）
 * <p>
 * 功能：ReAct 对话的"上下文中枢"，对上（PromptService/Case层）提供三个能力：
 * <pre>
 *   buildPromptContext()  聚合所有 Provider 的输出 --> PromptContextVO
 *   trimHistory()         在 token 预算内裁剪消息历史（默认 8000）
 * </pre>
 * 整体运行过程（一次请求内的调用时序）：
 * <pre>
 *   一次请求的上下文装配
 *        |
 *        | (1) trimHistory(history, 8000)
 *        |        |
 *        |        v
 *        |     HybridReducer = PriorityReducer ∩ SlidingWindowReducer + 保底2条
 *        |        |
 *        |        v
 *        |     裁剪结果作为本次请求的历史消息
 *        |
 *        | (2) PromptService.buildEnrichedMessage(...)
 *        |        |
 *        |        v
 *        |     buildPromptContext(sessionId, userId, terminalSessionId, history)
 *        |        |
 *        |        +--> for provider in providers(按order排序, 跳过disabled):
 *        |        |        TerminalState(10)  {osInfo, currentUser, currentDirectory}
 *        |        |        Task(20)           {taskDescription}
 *        |        |        Milestone(30)      {milestoneVOS}
 *        |        |     finalCtx.putAll(...)   合并所有键值对
 *        |        |
 *        |        v
 *        |     PromptContextVO --> DynamicPromptBuilder --> 消息前缀
 * </pre>
 * 装配机制：@Resource List<ContextProvider> 由 Spring 按类型自动收集
 * 全部 Provider 实现，@PostConstruct 时按 getOrder() 排序——新增 Provider
 * 只需加 @Component，无需修改本类。
 *
 * @author shiyi
 */
@Service
public class ChatContextService implements IChatContextService {

    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 8000;

    private final List<ContextProvider> providers;
    
    @Resource
    private HybridReducer hybridReducer;

    // 构造函数注入；
    public ChatContextService(List<ContextProvider> providers) {
        this.providers = providers;
        this.providers.sort(Comparator.comparingInt(ContextProvider::getOrder));
    }

    /**
     * 聚合所有已启用的 ContextProvider，构建最终给 PromptService 使用的 PromptContextVO。
     *
     * <p>执行过程：
     * <pre>
     *   1. Spring 已经把全部 ContextProvider 注入到 providers
     *   2. 构造函数中已按 getOrder() 从小到大排序
     *   3. 这里依次调用 provider.provide(...)
     *   4. 每个 provider 返回一个 Map，上下文字段通过 putAll 合并到 finalCtx
     *   5. 最后把 finalCtx 映射为 PromptContextVO
     * </pre>
     *
     * <p>案例：
     * <pre>
     *   当前 providers 返回结果：
     *
     *   TerminalStateProvider ->
     *   {
     *     osInfo=Linux ubuntu 22.04,
     *     currentUser=root,
     *     currentDirectory=/var/log/nginx
     *   }
     *
     *   TaskProvider ->
     *   {
     *     taskDescription=排查 nginx 502 错误
     *   }
     *
     *   MilestoneProvider ->
     *   {
     *     milestoneVOS=[用户要求查看 error.log, 工具执行出现 permission denied]
     *   }
     *
     *   最终聚合结果：
     *   PromptContextVO {
     *     osInfo=Linux ubuntu 22.04,
     *     currentUser=root,
     *     currentDirectory=/var/log/nginx,
     *     taskDescription=排查 nginx 502 错误,
     *     milestoneVOS=[...]
     *   }
     * </pre>
     *
     * <p>这样 PromptService 后面就不需要再分别调用多个 provider，
     * 它只需要消费一个聚合好的 PromptContextVO 即可。
     *
     * @param sessionId 当前会话 ID
     * @param userId 用户 ID
     * @param terminalSessionId 终端会话 ID
     * @param messageHistory 当前业务侧消息历史
     * @return 聚合完成的 PromptContextVO
     */
    @Override
    @SuppressWarnings("unchecked")
    public PromptContextVO buildPromptContext(String sessionId, String userId, String terminalSessionId, List<Map<String, Object>> messageHistory) {
        return buildPromptContext(sessionId, userId, terminalSessionId, "", messageHistory);
    }

    /**
     * 构建 Prompt 上下文（带主机标识）。
     * <p>
     * 带 hostKey 的版本会让长期记忆按主机收窄；不传时退化为 USER 级记忆，
     * 与老调用方（没有主机概念的场景）行为一致。
     */
    @SuppressWarnings("unchecked")
    public PromptContextVO buildPromptContext(String sessionId, String userId, String terminalSessionId,
                                             String hostKey, List<Map<String, Object>> messageHistory) {
        Map<String, Object> finalCtx = new HashMap<>();
        // 按放置位置分桶：稳定前缀 / 动态后缀。
        // 同一份数据仍然进 finalCtx，保证老字段（osInfo、taskDescription…）向后兼容。
        Map<String, Object> stableCtx = new HashMap<>();
        Map<String, Object> ephemeralCtx = new HashMap<>();

        for (ContextProvider provider : providers) {
            if (!provider.enabled()) continue;
            // 每个 Provider 只负责自己那一小块上下文，统一在这里汇总收口。
            Map<String, Object> ctx = provider.provide(sessionId, userId, terminalSessionId, hostKey, messageHistory);
            if (ctx != null) {
                finalCtx.putAll(ctx);
                if (provider.getPlacement() == ContextPlacement.STABLE_PREFIX) {
                    stableCtx.putAll(ctx);
                } else {
                    ephemeralCtx.putAll(ctx);
                }
            }
        }

        return PromptContextVO.builder()
                .osInfo((String) finalCtx.get("osInfo"))
                .currentUser((String) finalCtx.get("currentUser"))
                .currentDirectory((String) finalCtx.get("currentDirectory"))
                .serverInfo((String) finalCtx.get("serverInfo"))
                .milestoneVOS((List<MilestoneVO>) finalCtx.get("milestoneVOS"))
                // 长期记忆摘要（2-8 新增）：由 LongTermMemoryProvider(order=25) 召回并注入，
                // 经 DynamicPromptBuilder 渲染为 [长期记忆] 段落拼到用户消息前面。
                .longTermMemorySummary((String) finalCtx.get("longTermMemorySummary"))
                .taskDescription((String) finalCtx.get("taskDescription"))
                .stableContext(stableCtx)
                .ephemeralContext(ephemeralCtx)
                .build();
    }

    /**
     * 按 token 预算裁剪业务侧消息历史。
     *
     * <p>当前实际使用的是 HybridReducer，它内部会组合：
     * <ul>
     *   <li>PriorityReducer：保证关键错误、关键路径、tool 调用入口尽量不丢</li>
     *   <li>SlidingWindowReducer：保证最近一段连续上下文尽量不丢</li>
     *   <li>最近 2 个完整消息组保底：保证当前轮上下文不被裁断</li>
     * </ul>
     *
     * <p>案例：
     * <pre>
     *   原始历史：
     *   1. system: 你是运维助手
     *   2. user: 查看 /etc/nginx/nginx.conf
     *   3. assistant: 很长的解释...
     *   4. assistant(tool_calls)
     *   5. tool: permission denied
     *   6. user: 改查 /var/log/nginx/error.log
     *
     *   假设 tokenBudget 不够保留全部消息。
     *
     *   经过 HybridReducer 后，可能得到：
     *   1. system: 你是运维助手
     *   2. user: 查看 /etc/nginx/nginx.conf
     *   4. assistant(tool_calls)
     *   5. tool: permission denied
     *   6. user: 改查 /var/log/nginx/error.log
     * </pre>
     *
     * <p>也就是说，它不是简单按条数截断，而是尽量兼顾：
     * 重要性、时效性、工具调用链完整性。
     *
     * @param history 原始消息历史
     * @param tokenBudget token 预算；小于等于 0 时回退到默认值 8000
     * @return 裁剪后的消息历史
     */
    @Override
    public List<Map<String, Object>> trimHistory(List<Map<String, Object>> history, int tokenBudget) {
        if (history == null || history.isEmpty()) return Collections.emptyList();
        // 混合裁剪：既保关键消息，也保最近连续上下文。
        return hybridReducer.reduce(history, tokenBudget > 0 ? tokenBudget : DEFAULT_MAX_CONTEXT_TOKENS);
    }

}

