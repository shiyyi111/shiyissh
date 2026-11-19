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
import org.junit.Assume;
import org.junit.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 图链路真模型验证（可选执行）。
 * <p>
 * 与 {@link GraphAgentFlowTest} 的区别：这里不 mock 模型，直接构造真实 dashscope 客户端，
 * 跑一次"看图 → 调工具 → 读结果 → 作答"。没有 api-key 时自动跳过，不会卡 CI。
 * <p>
 * 运行方式：
 * <pre>
 * DASHSCOPE_API_KEY=sk-xxx mvn -pl shyissh-server-domain test -Dtest=GraphLiveVerifyTest
 * </pre>
 *
 * @author shiyi
 */
public class GraphLiveVerifyTest {

    @Test
    public void shouldRunWholeGraphWithRealModel() throws Exception {
        String apiKey = firstNonBlank(System.getProperty("llm.api-key"),
                System.getenv("DASHSCOPE_API_KEY"), System.getenv("LLM_API_KEY"));
        Assume.assumeTrue("未提供 llm.api-key / DASHSCOPE_API_KEY，跳过真模型验证", apiKey != null);

        FakeSshToolService ssh = new FakeSshToolService();
        GraphSshToolService sshTool = new GraphSshToolService(
                mock(com.shiyi.domain.ssh.service.ISshTerminalService.class), ssh);

        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(realChatModel(apiKey));

        SshAgentGraph graph = new SshAgentGraph(
                new IntentNode(), new EnrichNode(), new LlmNode(provider, sshTool),
                new GuardNode(), new ToolNode(sshTool), new ObserveNode(),
                new RejectNode(), new ApprovalNode(), new AnswerNode());

        TokenStreamBridge bridge = new TokenStreamBridge();
        List<String> tokens = new CopyOnWriteArrayList<>();
        bridge.tokens().subscribe(tokens::add);

        Map<String, Object> input = new HashMap<>();
        input.put(GraphStateKeys.MESSAGES, List.of((Message) new UserMessage("看看这台机器磁盘还剩多少空间")));
        input.put(GraphStateKeys.ROUND, 0);
        input.put(GraphStateKeys.MAX_ROUNDS, 3);
        input.put(GraphStateKeys.ANSWER_STREAMED, false);

        Optional<OverAllState> result = graph.compiled().invoke(input,
                RunnableConfig.builder()
                        .threadId("t-live-graph")
                        .addMetadata("tokenSink", bridge)
                        .build());

        assertTrue("图应该正常结束", result.isPresent());

        List<String> executed = ssh.executed();
        System.out.println("[PASS] 实际执行的命令 = " + executed);
        assertFalse("真模型应该至少调用一次工具", executed.isEmpty());
        assertTrue("第一条命令应该是 df 类查询，实际=" + executed,
                executed.get(0).contains("df"));

        String answer = result.get().value(GraphStateKeys.ANSWER, String.class).orElse("");
        System.out.println("[PASS] 最终回答 = " + answer);
        assertTrue("回答应引用工具输出里的真实数字 48%，实际=" + answer, answer.contains("48%"));

        String tokenText = String.join("", tokens);
        System.out.println("[PASS] token 流长度 = " + tokenText.length());
        assertFalse("token 流不应为空", tokenText.isBlank());
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * 构造真实模型客户端：与 {@code GraphChatModelConfig} 使用同一组默认值，
     * 避免"测试能跑、线上装配不生效"这类两边配置不一致的问题。
     */
    private ChatModel realChatModel(String apiKey) {
        String baseUrl = firstNonBlank(System.getProperty("llm.base-url"),
                System.getenv("LLM_BASE_URL"), "https://dashscope.aliyuncs.com/compatible-mode");
        String model = firstNonBlank(System.getProperty("llm.model"),
                System.getenv("LLM_MODEL"), "deepseek-v4.1-flash");
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .completionsPath("/v1/chat/completions")
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(0.0)
                        .build())
                .build();
    }
}