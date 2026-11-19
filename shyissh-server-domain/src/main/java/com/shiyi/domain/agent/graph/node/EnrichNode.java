package com.shiyi.domain.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.shiyi.domain.agent.graph.GraphStateKeys;
import com.shiyi.domain.agent.graph.support.GraphAgentRegistry;
import com.shiyi.domain.agent.graph.support.GraphMessages;
import com.shiyi.domain.agent.service.IPromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [enrich] 上下文富化节点。
 * <p>
 * 
 * <pre>
 *   输入：原始用户消息 + 历史 + 会话/终端 ID；输出：改写后的最后一条 UserMessage。
 *   SAA 版：[enrich] 节点           → enrichContents(messages, intent, ...)
 * </pre>
 * 调用的还是 {@link IPromptService#buildEnrichedMessage}（里面走 {@code PromptEnvelope}
 * 三段式信封 + 长期记忆 + 最近命令 + 环境信息），只是把"改 LlmRequest"换成了"改 messages"。
 * <p>
 * 为什么每轮都要重算：意图可能已经变了（上一轮排查、这一轮部署），
 * 历史也可能刚被工具结果改过，所以不能在入口算一次。
 *
 * @author shiyi
 */
@Component
public class EnrichNode implements GraphNode {

    private static final Logger log = LoggerFactory.getLogger(EnrichNode.class);

    /**
     * 兜底 instruction：PromptService 拼出来的信封里已经带了完整的稳定前缀，
     * 这里只在 system 缺失时补一条，避免模型没有角色设定。
     */
    private static final String FALLBACK_INSTRUCTION = """
            你是 Linux 运维助手，负责通过 SSH 在远端主机执行命令并解释结果。

            规则：
            1. 需要系统信息时，调用 executeCommand 工具执行命令，不要编造数据。
            2. 拿到工具输出后，必须立即用输出里的真实数字回答，不要再执行其他命令。
            3. 同一个命令不要重复执行。
            4. 回答用中文，简洁直接，给出关键数字和结论。
            """;

    /** 历史摘要消息的固定前缀：既用于展示，也用于判断"这条摘要是否已经拼过"。 */
    private static final String HISTORY_SUMMARY_PREFIX = "【会话摘要】";

    @Resource
    private IPromptService promptService;

    /** yml agents[].instruction 的来源；单测里手工 new 时为 null，回退 FALLBACK_INSTRUCTION。 */
    @Resource
    private GraphAgentRegistry agentRegistry;

    @Override
    public String name() {
        return GraphStateKeys.NODE_ENRICH;
    }

    @Override
    public AsyncNodeActionWithConfig action() {
        return AsyncNodeActionWithConfig.node_async((OverAllState state, RunnableConfig config) -> {
            // 发模型之前先折叠重复的工具输出：同一命令连打两次，第二次的输出只是把上一次复读一遍。
            // 折叠只动回填文本、不动 tool_call_id，配对关系保持完整；原文在 [observe] 已单独落库。
            List<Message> messages = new ArrayList<>(
                    GraphMessages.foldDuplicateToolOutputs(GraphMessages.messages(state)));
            String intent = state.value(GraphStateKeys.INTENT, "");
            String sessionId = state.value(GraphStateKeys.SESSION_ID, "");
            String userId = state.value(GraphStateKeys.USER_ID, "default");
            String terminalSessionId = state.value(GraphStateKeys.TERMINAL_SESSION_ID, "");
            // 主机标识由入口解析一次写进 state；这里只透传给上下文采集层，
            // 让它把 HOST 作用域的长期记忆限定在本机。
            String hostKey = state.value(GraphStateKeys.HOST_KEY, "");

            int userIndex = GraphMessages.lastUserIndex(messages);
            String rawText = GraphMessages.lastUserText(messages);
            List<Map<String, Object>> history = GraphMessages.buildHistory(messages, userIndex);
            List<String> recentCommands = GraphMessages.extractCommands(messages);

            String enriched = enrichContents(rawText, sessionId, userId, terminalSessionId,
                    recentCommands, history, intent, hostKey);

            // 用富化后的文本替换当前这轮的用户消息（保留 PromptEnvelope 信标）
            if (userIndex >= 0) {
                messages.set(userIndex, new UserMessage(enriched));
            } else {
                messages.add(new UserMessage(enriched));
            }

            // ① system 位只放 instruction：它每轮都必须存在（人格/规则不能丢），已有就保留。
            boolean hasSystem = !messages.isEmpty()
                    && messages.get(0) instanceof SystemMessage
                    && !String.valueOf(messages.get(0).getText()).isBlank();
            if (!hasSystem) {
                messages.add(0, new SystemMessage(resolveInstruction(state)));
            }

            // ② 历史摘要排在 instruction 之后。
            // 摘要内容由入口算好放进 state（不是塞在 MESSAGES[0]），这里只负责拼位置；
            // [enrich] 每轮都会执行（tool → observe → enrich 会回来），所以必须幂等：
            // 已经存在摘要消息时不再重复插入，否则环形循环几轮就会攒下好几份同样的摘要。
            String historySummary = state.value(GraphStateKeys.HISTORY_SUMMARY, "");
            if (historySummary != null && !historySummary.isBlank() && !containsSummary(messages)) {
                messages.add(1, new SystemMessage(HISTORY_SUMMARY_PREFIX + historySummary));
            }

            log.info("[enrich] 意图={}, 历史={}条, 最近命令={}条, 消息数={}",
                    intent, history.size(), recentCommands.size(), messages.size());

            Map<String, Object> delta = new HashMap<>();
            delta.put(GraphStateKeys.MESSAGES, messages);
            return delta;
        });
    }

    /**
     * 取 system instruction：优先 yml 里该 Agent 的 instruction，
     * 取不到才用兜底文案。子 Agent 会把自己的 instruction 带进来（AGENT_NAME 由图 state 传递）。
     */
    private String resolveInstruction(OverAllState state) {
        try {
            if (agentRegistry != null) {
                String agentName = state.value(GraphStateKeys.AGENT_NAME, "");
                var agent = agentRegistry.find(agentName);
                if (agent != null && agent.getInstruction() != null && !agent.getInstruction().isBlank()) {
                    return agent.getInstruction();
                }
            }
        } catch (Exception e) {
            log.warn("[enrich] 读取 yml instruction 失败，使用兜底: {}", e.getMessage());
        }
        return FALLBACK_INSTRUCTION;
    }

    /**
     * 消息列表里是否已经有历史摘要。
     * <p>
     * 用前缀判断而不是"有没有第二条 SystemMessage"：instruction 之外将来可能还有别的系统级提示，
     * 那时候按位置判断会误把别人的消息当成摘要。
     */
    private boolean containsSummary(List<org.springframework.ai.chat.messages.Message> messages) {
        for (org.springframework.ai.chat.messages.Message message : messages) {
            if (message instanceof SystemMessage system
                    && system.getText() != null
                    && system.getText().startsWith(HISTORY_SUMMARY_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 富化失败不能让整轮失败，退回原始文本继续。
     */
    private String enrichContents(String rawText, String sessionId, String userId,
                                  String terminalSessionId, List<String> recentCommands,
                                  List<Map<String, Object>> history, String intent, String hostKey) {
        try {
            String enriched = promptService.buildEnrichedMessage(
                    rawText, sessionId, userId, terminalSessionId, recentCommands, history, intent, hostKey);
            return (enriched == null || enriched.isBlank()) ? rawText : enriched;
        } catch (Exception e) {
            log.warn("[enrich] 富化异常，降级用原始文本: {}", e.getMessage());
            return rawText;
        }
    }
}