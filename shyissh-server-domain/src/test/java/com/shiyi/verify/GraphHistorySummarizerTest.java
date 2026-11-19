package com.shiyi.verify;

import com.shiyi.domain.agent.graph.support.GraphHistorySummarizer;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 历史摘要器的边界测试。
 * <p>
 * 这块逻辑最怕四件事：① 该省钱的时候乱调模型（每次请求多一次 LLM 调用）；
 * ② 摘要失败把主链路带崩；③ 失败与"不需要"混为一谈（前者要上报 summarySkipped）；
 * ④ 超长时截错方向（保留最远的、丢掉最近的）。断言重点就是这四件事。
 *
 * @author shiyi
 */
public class GraphHistorySummarizerTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<ChatModel> providerOf(ChatModel chatModel) {
        ObjectProvider<ChatModel> provider = (ObjectProvider<ChatModel>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(chatModel);
        return provider;
    }

    private List<Message> messages(int count) {
        List<Message> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String text = "消息 " + i + "：磁盘占用持续增长，日志目录与容器镜像占了大头，需要确认清理范围。"
                    + "已经查看了 df -h 与 du -sh 的输出，根分区使用率九成以上，暂时不影响业务。"
                    + "容器日志按天轮转但历史没有清理，镜像层占了十几个 G，稍后再确认是否可以直接删除旧镜像。"
                    + "另外注意到 /var/log/journal 也在增长，如果确认没有保留价值，可以限制系统日志的总大小。";
            list.add(i % 2 == 0 ? new UserMessage(text) : new AssistantMessage(text));
        }
        return list;
    }

    private ChatModel answeringModel(String text) {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        when(generation.getOutput()).thenReturn(new AssistantMessage(text));
        when(response.getResult()).thenReturn(generation);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
        return chatModel;
    }

    @Test
    public void shouldSkipModelWhenDroppedMessagesBelowThreshold() {
        ChatModel chatModel = mock(ChatModel.class);
        GraphHistorySummarizer summarizer = new GraphHistorySummarizer(providerOf(chatModel));

        // 只丢了 1 条（阈值 2），不值得为它花一次模型调用
        String summary = summarizer.summarize(messages(1));

        assertEquals("", summary);
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    public void shouldSkipModelWhenDroppedContentTooShort() {
        ChatModel chatModel = mock(ChatModel.class);
        GraphHistorySummarizer summarizer = new GraphHistorySummarizer(providerOf(chatModel));

        // 条数够了但内容太短（"好的""继续"这类），摘要的压缩收益接近零，不该花一次模型调用
        List<Message> shortOnes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            shortOnes.add(new UserMessage("好的 " + i));
        }

        assertEquals("", summarizer.summarize(shortOnes));
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    public void shouldReturnNullWhenNoChatModelAvailable() {
        GraphHistorySummarizer summarizer = new GraphHistorySummarizer(providerOf(null));

        // null = "该有摘要但要不到"，与空串"本来不需要"区分开，调用方据此上报 summarySkipped
        assertNull(summarizer.summarize(messages(6)));
    }

    @Test
    public void shouldReturnNullWhenModelCallFails() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("模型超时"));
        GraphHistorySummarizer summarizer = new GraphHistorySummarizer(providerOf(chatModel));

        // 摘要失败必须降级成"本轮没有摘要"，不能把异常抛给对话主流程
        assertNull(summarizer.summarize(messages(6)));
    }

    @Test
    public void shouldReturnTrimmedSummaryWhenModelAnswers() {
        ChatModel chatModel = answeringModel("  用户反映 nginx 重启失败，已确认 80 端口被占用。  ");
        GraphHistorySummarizer summarizer = new GraphHistorySummarizer(providerOf(chatModel));

        String summary = summarizer.summarize(messages(6));

        assertEquals("用户反映 nginx 重启失败，已确认 80 端口被占用。", summary);
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    public void shouldReturnEmptyWhenDroppedIsNullOrBlank() {
        ChatModel chatModel = mock(ChatModel.class);
        GraphHistorySummarizer summarizer = new GraphHistorySummarizer(providerOf(chatModel));

        assertEquals("", summarizer.summarize(null));
        // 6 条但全是空白内容时同样不该调模型
        List<Message> blanks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            blanks.add(new UserMessage("   "));
        }
        assertEquals("", summarizer.summarize(blanks));
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    public void shouldKeepRecentPartWhenTranscriptTooLong() {
        ChatModel chatModel = answeringModel("摘要");
        GraphHistorySummarizer summarizer = new GraphHistorySummarizer(providerOf(chatModel));

        List<Message> dropped = new ArrayList<>();
        // 最早的几条很长、最近的一条很短：截断后应当留下"最近"那句
        dropped.add(new UserMessage("最早的历史：" + "A".repeat(4000)));
        dropped.add(new AssistantMessage("早期的回复：" + "B".repeat(4000)));
        dropped.add(new UserMessage("最近一问：数据库连不上"));

        summarizer.summarize("s-1", dropped);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        String transcript = captor.getValue().getUserMessage().getText();
        assertNotNull(transcript);
        assertTrue("最近的消息必须保留", transcript.contains("最近一问：数据库连不上"));
        assertTrue("截断过要留提示", transcript.contains("更早的内容已省略"));
    }

    @Test
    public void shouldReuseCachedSummaryForSameDroppedBatch() {
        ChatModel chatModel = answeringModel("缓存摘要");
        GraphHistorySummarizer summarizer = new GraphHistorySummarizer(providerOf(chatModel));

        List<Message> dropped = messages(6);
        assertEquals("缓存摘要", summarizer.summarize("s-2", dropped));
        assertEquals("缓存摘要", summarizer.summarize("s-2", dropped));

        // 同一批被丢弃消息只该调一次模型：否则每轮请求都会重复烧一次 LLM 调用
        verify(chatModel, times(1)).call(any(Prompt.class));
    }
}
