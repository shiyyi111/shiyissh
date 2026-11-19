package com.shiyi.verify;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.shiyi.domain.agent.graph.GraphStateKeys;
import com.shiyi.domain.agent.graph.SshAgentGraph;
import com.shiyi.domain.agent.graph.node.AnswerNode;
import com.shiyi.domain.agent.graph.node.ApprovalNode;
import com.shiyi.domain.agent.graph.node.EnrichNode;
import com.shiyi.domain.agent.graph.node.GuardNode;
import com.shiyi.domain.agent.graph.node.IntentNode;
import com.shiyi.domain.agent.graph.node.LlmNode;
import com.shiyi.domain.agent.graph.node.ObserveNode;
import com.shiyi.domain.agent.graph.node.RejectNode;
import com.shiyi.domain.agent.graph.node.ToolNode;
import com.shiyi.domain.agent.graph.support.FakeSshToolService;
import com.shiyi.domain.agent.graph.support.GraphSshToolService;
import com.shiyi.domain.agent.graph.support.TokenStreamBridge;
import com.shiyi.domain.ssh.service.ISshTerminalService;
import org.junit.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审批断点重续：图在 [approval] 前停住 → 写回裁决 → 从同一条检查点继续，执行<b>原来那条</b>命令。
 * <p>
 * 这个测试不是在验证框架能不能中断（那是 SAA 的事），而是验证我们这条链路的约定：
 * <ol>
 *   <li>中断发生时命令一条都没执行（人还没批，绝不能先跑）；</li>
 *   <li>检查点里留着审批单号，上层能据此找到"停在哪";</li>
 *   <li>写回 approved 后恢复执行，跑的是当初那条命令、不是模型重新生成的另一条。</li>
 * </ol>
 *
 * @author shiyi
 */
public class ApprovalResumeTest {

    /** 命中 CONFIRM 规则（通用 rm -rf）：需要人工审批，但不属于"执行即毁灭"的 BLOCK。 */
    private static final String CONFIRM_COMMAND = "rm -rf /var/log/nginx";

    @Test
    public void shouldInterruptBeforeApprovalWithoutExecuting() throws Exception {
        FakeSshToolService ssh = new FakeSshToolService();
        SshAgentGraph graph = buildGraph(fakeModel(CONFIRM_COMMAND, "已清理日志目录。"), ssh, true);

        TokenStreamBridge bridge = new TokenStreamBridge();
        RunnableConfig config = config("t-approval-1", bridge);

        Optional<OverAllState> interrupted = graph.compiled().invoke(input("清理一下 nginx 日志"), config);

        assertTrue("中断时也要返回中断点的 state", interrupted.isPresent());
        assertTrue("人工批准前一条命令都不能执行", ssh.executed().isEmpty());
        String command = interrupted.get().value(GraphStateKeys.COMMAND, String.class).orElse("");
        assertEquals("检查点里存的就是这条待批命令", CONFIRM_COMMAND, command);
        assertEquals("这条命令必须先被判为需要确认", GuardNode.VERDICT_CONFIRM,
                interrupted.get().value(GraphStateKeys.GUARD_VERDICT, String.class).orElse(""));
        assertEquals("断点必须停在 [approval] 之前", GraphStateKeys.NODE_APPROVAL,
                graph.compiled().getState(config).next());
    }

    @Test
    public void shouldResumeFromCheckpointAndExecuteApprovedCommand() throws Exception {
        FakeSshToolService ssh = new FakeSshToolService();
        SshAgentGraph graph = buildGraph(fakeModel(CONFIRM_COMMAND, "已清理日志目录。"), ssh, true);

        TokenStreamBridge bridge = new TokenStreamBridge();
        List<String> tokens = new CopyOnWriteArrayList<>();
        bridge.tokens().subscribe(tokens::add);
        RunnableConfig config = config("t-approval-2", bridge);

        graph.compiled().invoke(input("清理一下 nginx 日志"), config);

        // 模拟人工批准：把裁决写回检查点，再从这里恢复
        graph.compiled().updateState(config, Map.of(
                GraphStateKeys.APPROVAL_DECISION, GraphStateKeys.APPROVAL_APPROVED,
                GraphStateKeys.ANSWER_STREAMED, false));
        OverAllState resumed = graph.resume(config).reduce((first, second) -> second)
                .map(output -> output.state())
                .block();

        assertNotNull("恢复后应该有最终 state", resumed);
        assertEquals("恢复后执行的必须是当初被拦下的那条命令", List.of(CONFIRM_COMMAND), ssh.executed());
        String answer = resumed.value(GraphStateKeys.ANSWER, String.class).orElse("");
        assertTrue("最终回答要能反映执行结果，实际=" + answer,
                answer.contains("已清理") || answer.contains("nginx"));
        assertFalse("批准路径不该以 need_approval 收口",
                ApprovalNode.STOP_REASON_NEED_APPROVAL.equals(
                        resumed.value(GraphStateKeys.STOP_REASON, String.class).orElse("")));
    }

    @Test
    public void shouldStopWithoutExecutingWhenRejected() throws Exception {
        FakeSshToolService ssh = new FakeSshToolService();
        SshAgentGraph graph = buildGraph(fakeModel(CONFIRM_COMMAND, "已清理日志目录。"), ssh, true);

        TokenStreamBridge bridge = new TokenStreamBridge();
        RunnableConfig config = config("t-approval-3", bridge);

        graph.compiled().invoke(input("清理一下 nginx 日志"), config);
        graph.compiled().updateState(config, Map.of(
                GraphStateKeys.APPROVAL_DECISION, GraphStateKeys.APPROVAL_REJECTED,
                GraphStateKeys.ANSWER_STREAMED, false));
        OverAllState resumed = graph.resume(config).reduce((first, second) -> second)
                .map(output -> output.state())
                .block();

        assertNotNull(resumed);
        assertTrue("驳回后命令不能执行", ssh.executed().isEmpty());
        assertEquals("驳回要有独立的收口原因", ApprovalNode.STOP_REASON_APPROVAL_REJECTED,
                resumed.value(GraphStateKeys.STOP_REASON, String.class).orElse(""));
    }

    private SshAgentGraph buildGraph(ChatModel model, FakeSshToolService ssh, boolean withCheckpoint)
            throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        GraphSshToolService sshTool = new GraphSshToolService(mock(ISshTerminalService.class), ssh);

        SshAgentGraph graph = new SshAgentGraph(
                new IntentNode(),
                new EnrichNode(),
                new LlmNode(provider, sshTool),
                new GuardNode(),
                new ToolNode(sshTool),
                new ObserveNode(),
                new RejectNode(),
                new ApprovalNode(),
                new AnswerNode());
        if (withCheckpoint) {
            // 生产里由 CheckpointConfig 以 bean 注入；单测直接反射塞进去，走同一段编译逻辑
            Field field = SshAgentGraph.class.getDeclaredField("checkpointSaver");
            field.setAccessible(true);
            field.set(graph, new MemorySaver());
        }
        return graph;
    }

    private RunnableConfig config(String threadId, TokenStreamBridge bridge) {
        return RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata("tokenSink", bridge)
                .build();
    }

    private Map<String, Object> input(String text) {
        Map<String, Object> input = new HashMap<>();
        input.put(GraphStateKeys.MESSAGES, List.of((Message) new UserMessage(text)));
        input.put(GraphStateKeys.ROUND, 0);
        input.put(GraphStateKeys.MAX_ROUNDS, 3);
        input.put(GraphStateKeys.ANSWER_STREAMED, false);
        return input;
    }

    /** 假模型：第一轮返回待审批的 toolCall，本批工具结果回来后给结论。 */
    private ChatModel fakeModel(String command, String finalAnswer) {
        AtomicInteger calls = new AtomicInteger(0);
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                if (calls.getAndIncrement() == 0) {
                    AssistantMessage message = AssistantMessage.builder()
                            .content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall(
                                    "call-0", "function", "executeCommand",
                                    "{\"command\":\"" + command + "\"}")))
                            .build();
                    return new ChatResponse(new ArrayList<>(List.of(new Generation(message))));
                }
                return textResponse(finalAnswer);
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                String[] chunks = finalAnswer.split("(?<=\\G.{2})");
                return Flux.fromArray(chunks).map(this::textResponse);
            }

            private ChatResponse textResponse(String text) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
            }
        };
    }
}