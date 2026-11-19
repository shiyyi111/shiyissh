package com.shiyi.verify;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.shiyi.domain.agent.graph.GraphStateKeys;
import com.shiyi.domain.agent.graph.node.ApprovalNode;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * [approval] 节点验证。
 * <p>
 * 这个节点只做一件事：把 [guard] 写好的确认提示搬到 {@code ANSWER}，并标记
 * {@code stopReason=need_approval}。它必须做到"三不"——不执行命令、不改 verdict、
 * 不把这一轮当成正常答完（否则前端会把"等你确认"渲染成"已处理完"）。
 *
 * @author shiyi
 */
public class ApprovalNodeTest {

    private final ApprovalNode node = new ApprovalNode();

    @Test
    public void shouldCarryGuardPromptToAnswer() throws Exception {
        Map<String, Object> delta = run(Map.of(
                GraphStateKeys.APPROVAL_PROMPT, "这条命令需要你确认后才能执行：sudo reboot",
                GraphStateKeys.APPROVAL_ID, "ap-1"));

        assertEquals("这条命令需要你确认后才能执行：sudo reboot", delta.get(GraphStateKeys.ANSWER));
        assertEquals(ApprovalNode.STOP_REASON_NEED_APPROVAL, delta.get(GraphStateKeys.STOP_REASON));
        assertEquals("文本由 [answer] 节点负责发出，这里不能再流式一遍",
                Boolean.FALSE, delta.get(GraphStateKeys.ANSWER_STREAMED));
    }

    @Test
    public void shouldFallbackWhenPromptMissing() throws Exception {
        Map<String, Object> delta = run(Map.of());

        String answer = String.valueOf(delta.get(GraphStateKeys.ANSWER));
        assertEquals("没有提示文本时也要给用户一个能看懂的去向",
                true, answer.contains("需要人工确认"));
        assertEquals(ApprovalNode.STOP_REASON_NEED_APPROVAL, delta.get(GraphStateKeys.STOP_REASON));
    }

    @Test
    public void shouldNotTouchVerdictOrExecuteAnything() throws Exception {
        Map<String, Object> delta = run(Map.of(
                GraphStateKeys.APPROVAL_PROMPT, "需要确认",
                GraphStateKeys.GUARD_VERDICT, "CONFIRM"));

        assertFalse("approval 节点不参与判定，verdict 仍由 [guard] 维护",
                delta.containsKey(GraphStateKeys.GUARD_VERDICTS));
        assertFalse("提示分支不产生工具输出", delta.containsKey(GraphStateKeys.TOOL_OUTPUTS));
    }

    @Test
    public void shouldExposeNodeName() {
        assertEquals(GraphStateKeys.NODE_APPROVAL, node.name());
    }

    private Map<String, Object> run(Map<String, Object> input) throws Exception {
        Map<String, Object> result = node.action()
                .apply(new OverAllState(new HashMap<>(input)), RunnableConfig.builder().build())
                .get();
        return result == null ? Map.of() : result;
    }
}