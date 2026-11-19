package com.shiyi.verify;

import com.shiyi.domain.agent.graph.node.LlmNode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 意图 → 工具可见性 的裁剪规则验证。
 * <p>
 * 裁剪的目标不是"少给工具显得安全"，而是减少两种真实的坏行为：
 * <ol>
 *   <li>闲聊时模型真的去执行命令（"今天天气怎么样" → {@code df -h}），白算 token、白开审批；</li>
 *   <li>纯解释类问题不该有写操作入口，但它仍然需要查历史结论，所以记忆工具要留。</li>
 * </ol>
 * 同时这里也守着"宁松勿错"：意图为空 / UNKNOWN / COMPOUND / CONTINUE 必须走全量，
 * 否则一次误判就会让模型"看不见工具"，用户看到的是"它明明能连机器却什么都不做"。
 *
 * @author shiyi
 */
public class GraphIntentToolPolicyTest {

    @Test
    public void chatShouldExposeNoTools() {
        assertEquals(LlmNode.ToolPolicy.NONE, LlmNode.toolPolicyOf("CHAT"));
        assertFalse(LlmNode.toolPolicyOf("CHAT").allowsShell());
        assertFalse(LlmNode.toolPolicyOf("CHAT").allowsDispatch());
        assertFalse(LlmNode.toolPolicyOf("CHAT").allowsMemory());
    }

    @Test
    public void explainShouldExposeMemoryOnly() {
        LlmNode.ToolPolicy policy = LlmNode.toolPolicyOf("EXPLAIN");
        assertEquals(LlmNode.ToolPolicy.MEMORY_ONLY, policy);
        assertFalse("解释类问题不执行任何命令", policy.allowsShell());
        assertFalse(policy.allowsDispatch());
        assertTrue("但允许查/记历史结论", policy.allowsMemory());
    }

    @Test
    public void businessIntentsShouldStayFull() {
        for (String intent : new String[]{"DIAGNOSE", "CONFIGURE", "DEPLOY", "MONITOR",
                "SECURITY", "BACKUP", "EXECUTE", "SEARCH"}) {
            assertEquals(intent + " 是业务意图，必须全量", LlmNode.ToolPolicy.FULL,
                    LlmNode.toolPolicyOf(intent));
        }
    }

    @Test
    public void uncertainIntentsShouldStayFull() {
        for (String intent : new String[]{"", "  ", "UNKNOWN", "COMPOUND", "CONTINUE", null}) {
            assertEquals("意图不确定时不能收窄工具集，否则误判会让模型无从下手",
                    LlmNode.ToolPolicy.FULL, LlmNode.toolPolicyOf(intent));
        }
    }

    @Test
    public void shouldTolerateCaseAndPadding() {
        assertEquals(LlmNode.ToolPolicy.NONE, LlmNode.toolPolicyOf("  chat  "));
        assertEquals(LlmNode.ToolPolicy.MEMORY_ONLY, LlmNode.toolPolicyOf("Explain"));
    }
}