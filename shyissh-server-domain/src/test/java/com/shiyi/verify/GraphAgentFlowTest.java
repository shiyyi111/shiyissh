package com.shiyi.verify;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 图链路端到端测试（假模型 + 假 SSH，完全离线）。
 * <p>
 * 验证的不是"能编译"，而是三件真事：
 * 1) 模型给的 toolCall 参数原样进到工具里（不再是空串或伪造）；
 * 2) 工具结果被回填进消息历史，模型第二轮基于真实数字作答；
 * 3) 危险命令走 [guard]→[reject]，工具一次都没被执行。
 *
 * @author shiyi
 */
public class GraphAgentFlowTest {

    @Test
    public void shouldRunFullReActLoopAndAnswerWithRealNumbers() throws Exception {
        FakeSshToolService ssh = new FakeSshToolService();
        ChatModel model = fakeModel(List.of("df -h"), "磁盘总量 40G，已用 18G，剩余 20G，使用率 48%。");
        SshAgentGraph graph = buildGraph(model, ssh);

        TokenStreamBridge bridge = new TokenStreamBridge();
        List<String> tokens = new CopyOnWriteArrayList<>();
        bridge.tokens().subscribe(tokens::add);

        Optional<OverAllState> result = graph.compiled().invoke(
                input("看看磁盘空间还剩多少"),
                config("t-full-loop", bridge));

        assertTrue("图应该正常结束", result.isPresent());
        assertEquals("模型必须真的把 df -h 传进工具", List.of("df -h"), ssh.executed());

        String answer = result.get().value(GraphStateKeys.ANSWER, String.class).orElse("");
        assertTrue("回答必须引用真实数字 48%，实际=" + answer, answer.contains("48%"));

        String tokenText = String.join("", tokens);
        assertTrue("token 流应该有内容，实际=" + tokenText, tokenText.contains("48%"));
    }

    @Test
    public void shouldRejectDangerousCommandWithoutExecuting() throws Exception {
        FakeSshToolService ssh = new FakeSshToolService();
        ChatModel model = fakeModel(List.of("mkfs.ext4 /dev/sdb"), "");
        SshAgentGraph graph = buildGraph(model, ssh);

        Optional<OverAllState> result = graph.compiled().invoke(
                input("把根目录删了"),
                config("t-reject", new TokenStreamBridge()));

        assertTrue(result.isPresent());
        assertTrue("危险命令不能被执行", ssh.executed().isEmpty());
        String answer = result.get().value(GraphStateKeys.ANSWER, String.class).orElse("");
        assertTrue("应该给出拦截说明，实际=" + answer, answer.contains("拦截"));
        assertFalse("不该断言成功执行", answer.contains("已执行"));
    }

    @Test
    public void shouldStreamFinalAnswerWhenRoundsExhausted() throws Exception {
        FakeSshToolService ssh = new FakeSshToolService();
        // 前两轮都返回 toolCall，第三轮才给答案 —— 模拟"模型不肯收口"
        ChatModel model = fakeModel(List.of("df -h", "free -m"), "内存总量 2G，可用 1.2G。");
        SshAgentGraph graph = buildGraph(model, ssh);

        TokenStreamBridge bridge = new TokenStreamBridge();
        List<String> tokens = new CopyOnWriteArrayList<>();
        bridge.tokens().subscribe(tokens::add);

        Optional<OverAllState> result = graph.compiled().invoke(
                input("看下磁盘和内存"),
                config("t-max-rounds", bridge));

        assertTrue(result.isPresent());
        assertEquals("两轮工具都要执行", List.of("df -h", "free -m"), ssh.executed());
        assertEquals("第三轮必须收口", 3,
                result.get().value(GraphStateKeys.ROUND, Integer.class).orElse(0).intValue());
        String tokenText = String.join("", tokens);
        assertTrue("末轮应该走流式，实际 token=" + tokenText, tokenText.contains("1.2G"));
    }

    private SshAgentGraph buildGraph(ChatModel model, FakeSshToolService ssh) throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        // 离线场景：没有绑定终端会话时 GraphSshToolService 会自动回退到 FakeSshToolService，
        // 这里 mock 一个"永远查不到会话"的终端服务即可。
        GraphSshToolService sshTool = new GraphSshToolService(mock(ISshTerminalService.class), ssh);

        return new SshAgentGraph(
                new IntentNode(),
                new EnrichNode(),
                new LlmNode(provider, sshTool),
                new GuardNode(),
                new ToolNode(sshTool),
                new ObserveNode(),
                new RejectNode(),
                new ApprovalNode(),
                new AnswerNode());
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

    /**
     * 假模型：按顺序返回 toolCall，用完之后返回文本；stream 永远切块吐文本。
     */
    private ChatModel fakeModel(List<String> commands, String finalAnswer) {
        AtomicInteger calls = new AtomicInteger(0);
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                int index = calls.getAndIncrement();
                if (index < commands.size()) {
                    return toolCallResponse(commands.get(index), "call-" + index);
                }
                return textResponse(finalAnswer);
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                String answer = finalAnswer == null ? "" : finalAnswer;
                String[] chunks = answer.isEmpty() ? new String[]{""} : answer.split("(?<=\\G.{2})");
                return Flux.fromArray(chunks).map(this::textResponse);
            }

            private ChatResponse textResponse(String text) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
            }
        };
    }

    private ChatResponse toolCallResponse(String command, String callId) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        callId, "function", "executeCommand",
                        "{\"command\":\"" + command + "\"}")))
                .build();
        return new ChatResponse(new ArrayList<>(List.of(new Generation(message))));
    }
}