package com.shiyi.domain.agent.graph.node;

import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.shiyi.domain.agent.graph.GraphStateKeys;
import com.shiyi.domain.agent.graph.support.TokenStreamBridge;
import com.shiyi.domain.agent.service.ILongTermMemoryService;
import com.shiyi.domain.agent.service.IPromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * [answer] 收口节点。
 * <p>
 * 负责两件事：
 * <ol>
 *   <li>把最终回答推给 SSE 并关闭 token 流：如果回答在 [llm] 节点里已经流式推过
 *       （{@code answerStreamed=true}），这里只补一个 complete；如果是 [reject] 直接生成的
 *       （没经过模型），这里补推全文；</li>
 *   <li>把 assistant 最终回答落库，下一轮历史回读靠它接上。</li>
 * </ol>
 *
 * @author shiyi
 */
@Component
public class AnswerNode implements GraphNode {

    private static final Logger log = LoggerFactory.getLogger(AnswerNode.class);

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    /** 提示词服务：清会话级里程碑缓存。 */
    @Resource
    private IPromptService promptService;

    @Override
    public String name() {
        return GraphStateKeys.NODE_ANSWER;
    }

    @Override
    public AsyncNodeActionWithConfig action() {
        return AsyncNodeActionWithConfig.node_async((state, config) -> {
            String answer = state.<String>value(GraphStateKeys.ANSWER).orElse("");
            boolean streamed = state.<Boolean>value(GraphStateKeys.ANSWER_STREAMED).orElse(false);
            TokenStreamBridge bridge = config.metadata("tokenSink").map(TokenStreamBridge.class::cast).orElse(null);

            log.info("[answer] streamed={}, answer={}", streamed,
                    answer.length() > 500 ? answer.substring(0, 500) + "..." : answer);

            // assistant 回答落库，多轮上下文靠它接上
            saveAssistantMessageQuietly(state, answer);

            // 会话收口清理。
            // 这两个组件内部都是 ConcurrentHashMap，只进不出——不清就会按 sessionId 无限累积，
            // 长时间运行必然 OOM。放在 answer 节点是因为它是所有正常路径的唯一出口。
            clearSessionCachesQuietly(state);

            if (bridge != null) {
                if (!streamed && answer != null && !answer.isBlank()) {
                    bridge.emit(answer);
                }
                bridge.complete();
            }

            // 兜底：没走过 [llm] 收口轮、也没走 [reject] 的路径（理论上不存在），
            // 统一记成 completed，保证 done 事件里这个字段永远有值，前端不用做空判断。
            Map<String, Object> delta = new HashMap<>();
            String stopReason = state.value(GraphStateKeys.STOP_REASON, "");
            if (stopReason.isBlank()) {
                delta.put(GraphStateKeys.STOP_REASON, "completed");
            }
            return delta;
        });
    }

    /**
     * 职责：保存失败不影响本次对话。
     */
    private void saveAssistantMessageQuietly(com.alibaba.cloud.ai.graph.OverAllState state, String answer) {
        if (answer == null || answer.isBlank()) {
            return;
        }
        String sessionId = state.value(GraphStateKeys.SESSION_ID, "");
        String userId = state.value(GraphStateKeys.USER_ID, "default");
        int completionTokens = state.value(GraphStateKeys.COMPLETION_TOKENS, 0);
        String hostKey = state.value(GraphStateKeys.HOST_KEY, "");
        try {
            // 带上模型返回的真实 completion token；取不到时由实现退回长度估算。
            // hostKey 让"根因/建议"类结论绑定到具体主机，换机器不会被错误召回。
            longTermMemoryService.saveAssistantMessage(userId, sessionId, answer, completionTokens, hostKey);
        } catch (Exception e) {
            log.warn("[answer] 保存助手消息失败: {}", e.getMessage());
        }
    }

    /**
     * 清会话级内存缓存，防止 {@code ConcurrentHashMap} 随会话数无限增长。
     * <p>
     * 
     * <pre>
     *   promptService.clearMilestones(sessionId);            // MilestoneTracker 的里程碑队列
     * </pre>
     * 清理时机是"一次对话结束"，因此不会影响同会话的下一次对话（那些数据已落库，下次可重读）。
     */
    private void clearSessionCachesQuietly(com.alibaba.cloud.ai.graph.OverAllState state) {
        String sessionId = state.value(GraphStateKeys.SESSION_ID, "");
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            if (promptService != null) {
                promptService.clearMilestones(sessionId);
            }
        } catch (Exception e) {
            log.warn("[answer] 清理里程碑缓存失败: {}", e.getMessage());
        }
    }
}

