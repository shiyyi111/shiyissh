package com.shiyi.domain.agent.graph.node;

import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.shiyi.domain.agent.graph.GraphStateKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * [reject] 拒绝节点。
 * <p>
 * 危险命令不走 [tool]，而是走到这里：生成一句确定的拒绝说明，直接去 [answer]。
 * 这与 插件回调里的拦截有本质区别 —— 插件只能"返回一个字符串假装是工具结果"，
 * 模型可能继续基于这个假结果编故事；图里则是流程改道，模型根本没机会参与。
 *
 * @author shiyi
 */
@Component
public class RejectNode implements GraphNode {

    private static final Logger log = LoggerFactory.getLogger(RejectNode.class);

    @Override
    public String name() {
        return GraphStateKeys.NODE_REJECT;
    }

    @Override
    public AsyncNodeActionWithConfig action() {
        return AsyncNodeActionWithConfig.node_async((state, config) -> {
            String command = state.<String>value(GraphStateKeys.COMMAND).orElse("");
            String reason = state.<String>value(GraphStateKeys.GUARD_REASON).orElse("命中安全策略");

            String answer = "⚠️ 该命令已被安全策略拦截，未执行。\n\n"
                    + "命令：" + command + "\n"
                    + "原因：" + reason + "\n\n"
                    + "如果确认需要执行，请改为手动操作，或调整策略后重试。";

            log.warn("[reject] command={}, reason={}", command, reason);

            Map<String, Object> delta = new HashMap<>();
            delta.put(GraphStateKeys.ANSWER, answer);
            delta.put(GraphStateKeys.ANSWER_STREAMED, false);
            // 被安全策略拦下属于"没跑完的正常结局"，不是系统错误，单独一个原因值。
            delta.put(GraphStateKeys.STOP_REASON, "intent_reject");
            return delta;
        });
    }
}