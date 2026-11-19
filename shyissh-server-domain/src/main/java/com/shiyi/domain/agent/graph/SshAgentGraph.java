package com.shiyi.domain.agent.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.shiyi.domain.agent.graph.node.AnswerNode;
import com.shiyi.domain.agent.graph.node.ApprovalNode;
import com.shiyi.domain.agent.graph.node.EnrichNode;
import com.shiyi.domain.agent.graph.node.GraphNode;
import com.shiyi.domain.agent.graph.node.GuardNode;
import com.shiyi.domain.agent.graph.node.IntentNode;
import com.shiyi.domain.agent.graph.node.LlmNode;
import com.shiyi.domain.agent.graph.node.ObserveNode;
import com.shiyi.domain.agent.graph.node.RejectNode;
import com.shiyi.domain.agent.graph.node.ToolNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * SSH 运维 ReAct 图装配。
 * <p>
 * 图的形状：
 * <pre>
 * START → [intent] → [enrich] → [llm]
 *                                  ├─ 无 toolCall ─────────────→ [answer] → END
 *                                  └─ 有 toolCall → [guard]
 *                                                     ├─ BLOCK   → [reject]   → [answer] → END
 *                                                     ├─ CONFIRM → ⏸ 中断 ── 人工审批 ──▶ [approval]
 *                                                     │                                   ├─ approved → [tool] → [observe] → 回到 [enrich]
 *                                                     │                                   └─ rejected → [answer] → END
 *                                                     └─ PASS    → [tool] → [observe] → 回到 [enrich]
 * </pre>
 * 唯一的回边 observe→enrich 就是 ReAct 的循环本体 —— 循环归我们，不归框架。
 * <p>
 * <b>断点重续</b>：容器里有 {@link BaseCheckpointSaver} 时，编译期在 [approval] 之前埋一个断点。
 * CONFIRM 的命令会走到断点处暂停、把整个 state 存进检查点；用户审批后
 * 用 {@code updateState} 写回裁决、{@code withResume()} 从断点继续，
 * 已批准的命令直接进 [tool] 执行 —— 不需要重新发起一轮请求让模型再生成一遍工具调用。
 * 没有 saver（例如单测直接 new）时退化为"不埋断点"，其余边不变。
 *
 * @author shiyi
 */
@Component
public class SshAgentGraph {

    private static final Logger log = LoggerFactory.getLogger(SshAgentGraph.class);

    /**
     * 检查点存储。
     * <p>
     * 可选依赖：装配了才埋断点。用字段注入而不是构造器注入，是为了让单测
     * {@code new SshAgentGraph(...)} 不用造 saver，也让"没有 saver"成为合法的降级形态。
     */
    @Autowired(required = false)
    private BaseCheckpointSaver checkpointSaver;

    private volatile CompiledGraph compiledGraph;

    private final List<GraphNode> nodes;

    public SshAgentGraph(IntentNode intentNode,
                         EnrichNode enrichNode,
                         LlmNode llmNode,
                         GuardNode guardNode,
                         ToolNode toolNode,
                         ObserveNode observeNode,
                         RejectNode rejectNode,
                         ApprovalNode approvalNode,
                         AnswerNode answerNode) {
        this.nodes = List.of(intentNode, enrichNode, llmNode, guardNode,
                toolNode, observeNode, rejectNode, approvalNode, answerNode);
    }

    /**
     * 容器启动时装配。字段注入（saver）在构造器之后、@PostConstruct 之前完成，
     * 因此这里能拿到 saver；单测不走这个钩子，由 {@link #compiled()} 首次访问时惰性装配。
     */
    @PostConstruct
    public void init() {
        compiled();
    }

    /**
     * 惰性装配：既服务于 Spring 生命周期，也让单测直接 new 之后能立刻取图。
     */
    private CompiledGraph build() {
        try {
            StateGraph graph = new StateGraph("ssh-agent", GraphStateKeys.keyStrategies());

            for (GraphNode node : nodes) {
                graph.addNode(node.name(), node.action());
            }

            graph.addEdge(StateGraph.START, GraphStateKeys.NODE_INTENT);
            graph.addEdge(GraphStateKeys.NODE_INTENT, GraphStateKeys.NODE_ENRICH);
            graph.addEdge(GraphStateKeys.NODE_ENRICH, GraphStateKeys.NODE_LLM);

            // [llm] 出口：有工具调用就去安检，没有就直接收口
            graph.addConditionalEdges(GraphStateKeys.NODE_LLM,
                    AsyncEdgeAction.edge_async(state -> {
                        List<AssistantMessage.ToolCall> toolCalls =
                                state.<List<AssistantMessage.ToolCall>>value(GraphStateKeys.TOOL_CALLS)
                                        .orElse(List.of());
                        return toolCalls.isEmpty() ? GraphStateKeys.NODE_ANSWER : GraphStateKeys.NODE_GUARD;
                    }),
                    Map.of(GraphStateKeys.NODE_ANSWER, GraphStateKeys.NODE_ANSWER,
                            GraphStateKeys.NODE_GUARD, GraphStateKeys.NODE_GUARD));

            // [guard] 出口：BLOCK → 拒绝；CONFIRM → 人工审批；其余 → 执行
            graph.addConditionalEdges(GraphStateKeys.NODE_GUARD,
                    AsyncEdgeAction.edge_async(state -> {
                        String verdict = state.<String>value(GraphStateKeys.GUARD_VERDICT).orElse("");
                        if (GuardNode.VERDICT_BLOCK.equals(verdict)) {
                            return GraphStateKeys.NODE_REJECT;
                        }
                        if (GuardNode.VERDICT_CONFIRM.equals(verdict)) {
                            return GraphStateKeys.NODE_APPROVAL;
                        }
                        return GraphStateKeys.NODE_TOOL;
                    }),
                    Map.of(GraphStateKeys.NODE_REJECT, GraphStateKeys.NODE_REJECT,
                            GraphStateKeys.NODE_APPROVAL, GraphStateKeys.NODE_APPROVAL,
                            GraphStateKeys.NODE_TOOL, GraphStateKeys.NODE_TOOL));

            // [approval] 出口：批准 → 继续执行；驳回 → 收口说明已取消
            graph.addConditionalEdges(GraphStateKeys.NODE_APPROVAL,
                    AsyncEdgeAction.edge_async(state -> {
                        String verdict = state.<String>value(GraphStateKeys.GUARD_VERDICT).orElse("");
                        return GuardNode.VERDICT_PASS.equals(verdict)
                                ? GraphStateKeys.NODE_TOOL
                                : GraphStateKeys.NODE_ANSWER;
                    }),
                    Map.of(GraphStateKeys.NODE_TOOL, GraphStateKeys.NODE_TOOL,
                            GraphStateKeys.NODE_ANSWER, GraphStateKeys.NODE_ANSWER));

            graph.addEdge(GraphStateKeys.NODE_TOOL, GraphStateKeys.NODE_OBSERVE);
            // 循环本体：拿到工具结果，重新富化上下文，再问模型
            graph.addEdge(GraphStateKeys.NODE_OBSERVE, GraphStateKeys.NODE_ENRICH);
            graph.addEdge(GraphStateKeys.NODE_REJECT, GraphStateKeys.NODE_ANSWER);
            graph.addEdge(GraphStateKeys.NODE_ANSWER, StateGraph.END);

            CompileConfig.Builder config = CompileConfig.builder().recursionLimit(20);
            if (checkpointSaver != null) {
                config.saverConfig(SaverConfig.builder().register(checkpointSaver).build());
                // 只在 [approval] 之前埋断点：正常路径（PASS）根本不经过它，不受影响
                config.interruptBefore(GraphStateKeys.NODE_APPROVAL);
            }
            CompiledGraph compiled = graph.compile(config.build());
            log.info("[graph] ssh-agent 图装配完成，节点数={}，断点={}",
                    nodes.size(), checkpointSaver == null ? "未启用" : GraphStateKeys.NODE_APPROVAL);
            return compiled;
        } catch (Exception e) {
            throw new IllegalStateException("SSH Agent 图装配失败：" + e.getMessage(), e);
        }
    }

    public CompiledGraph compiled() {
        CompiledGraph compiled = this.compiledGraph;
        if (compiled == null) {
            synchronized (this) {
                compiled = this.compiledGraph;
                if (compiled == null) {
                    compiled = build();
                    this.compiledGraph = compiled;
                }
            }
        }
        return compiled;
    }

    public Flux<NodeOutput> stream(Map<String, Object> input, RunnableConfig config) {
        return compiled().stream(input, config);
    }

    public boolean checkpointEnabled() {
        return checkpointSaver != null;
    }

    /**
     * 从断点继续执行。
     * <p>
     * 调用前提：上一次执行在 [approval] 之前真的中断过，并且 state 已写回裁决
     * （见 {@code GraphChatService#resumeApproval}）。输入为空 Map —— 恢复不需要新的用户消息，
     * 一切都从检查点里的 state 恢复。
     */
    public Flux<NodeOutput> resume(RunnableConfig config) {
        return compiled().stream(Map.of(), config.withResume());
    }
}