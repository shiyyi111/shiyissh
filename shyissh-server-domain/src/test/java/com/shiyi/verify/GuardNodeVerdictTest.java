package com.shiyi.verify;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.shiyi.domain.agent.graph.GraphStateKeys;
import com.shiyi.domain.agent.graph.node.GuardNode;
import com.shiyi.domain.agent.model.entity.AgentApprovalEntity;
import com.shiyi.domain.agent.service.approval.AgentApprovalService;
import org.junit.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [guard] 节点逐条判定验证。
 * <p>
 * 守两个关键不变量：
 * <ol>
 *   <li><b>CONFIRM 档不能被当 PASS 放行</b>：{@code rm -rf /home/app}、{@code systemctl stop firewalld}
 *       这类命令一旦静默执行，后果不可逆，必须先落审批单、由人决策。</li>
 *   <li><b>一条危险命令不能废掉整批</b>：{@code df -h} + {@code rm -rf /} 的混合批次里，
 *       安全的只读命令应当照常执行，危险的单独拦下。</li>
 * </ol>
 *
 * @author shiyi
 */
public class GuardNodeVerdictTest {

    private final GuardNode node = new GuardNode();

    @Test
    public void confirmCommandShouldNotBeTreatedAsPass() throws Exception {
        Map<String, Object> delta = verdict(Map.of(GraphStateKeys.TOOL_CALLS,
                List.of(call("call-1", "executeCommand", "sudo systemctl restart nginx"))));

        assertEquals("CONFIRM 档必须单独成档，不能当 PASS",
                GuardNode.VERDICT_CONFIRM, delta.get(GraphStateKeys.GUARD_VERDICT));
        assertEquals(GuardNode.VERDICT_CONFIRM, verdicts(delta).get("call-1"));
        assertTrue("原因要能回灌给模型/用户", reasons(delta).get("call-1").contains("root"));
    }

    @Test
    public void mixedBatchShouldRejectWholeBatch() throws Exception {
        Map<String, Object> delta = verdict(Map.of(GraphStateKeys.TOOL_CALLS, List.of(
                call("call-safe", "executeCommand", "df -h"),
                call("call-danger", "executeCommand", "mkfs.ext4 /dev/sda1"))));

        // 混合批整批拒绝：审批一次只覆盖一条命令，未展示的命令不能顺带执行
        assertEquals(GuardNode.VERDICT_BLOCK, delta.get(GraphStateKeys.GUARD_VERDICT));
        assertEquals(GuardNode.VERDICT_PASS, verdicts(delta).get("call-safe"));
        assertEquals(GuardNode.VERDICT_BLOCK, verdicts(delta).get("call-danger"));
    }

    @Test
    public void allBlockedBatchShouldBeRejected() throws Exception {
        Map<String, Object> delta = verdict(Map.of(GraphStateKeys.TOOL_CALLS, List.of(
                call("call-1", "executeCommand", "mkfs.ext4 /dev/sda1"),
                call("call-2", "executeCommand", "kill -9 1"))));

        assertEquals("全部命中 BLOCK 才整批拒绝（省一次模型调用）",
                GuardNode.VERDICT_BLOCK, delta.get(GraphStateKeys.GUARD_VERDICT));
        assertEquals(GuardNode.VERDICT_BLOCK, verdicts(delta).get("call-1"));
        assertEquals(GuardNode.VERDICT_BLOCK, verdicts(delta).get("call-2"));
    }

    @Test
    public void safeBatchShouldPass() throws Exception {
        Map<String, Object> delta = verdict(Map.of(GraphStateKeys.TOOL_CALLS,
                List.of(call("call-1", "executeCommand", "df -h"))));

        assertEquals(GuardNode.VERDICT_PASS, delta.get(GraphStateKeys.GUARD_VERDICT));
        assertEquals(GuardNode.VERDICT_PASS, verdicts(delta).get("call-1"));
    }

    @Test
    public void approvedCommandShouldSkipRule() throws Exception {
        inject(node, "approvalService", mock(AgentApprovalService.class));
        Map<String, Object> input = new HashMap<>();
        input.put(GraphStateKeys.TOOL_CALLS,
                List.of(call("call-1", "executeCommand", "sudo systemctl restart nginx")));
        // 审批端复跑时把"被批准的那条命令"带进来，[guard] 应当直接放行
        input.put(GraphStateKeys.APPROVED_COMMAND, "sudo systemctl restart nginx");

        Map<String, Object> delta = verdict(input);

        assertEquals(GuardNode.VERDICT_PASS, delta.get(GraphStateKeys.GUARD_VERDICT));
        assertEquals(GuardNode.VERDICT_PASS, verdicts(delta).get("call-1"));
    }

    @Test
    public void approvedCommandShouldNotCoverAnotherCommand() throws Exception {
        inject(node, "approvalService", mock(AgentApprovalService.class));
        Map<String, Object> input = new HashMap<>();
        // 批准的是重启 nginx，模型却换成了删目录 —— 换命令搭便车必须仍走 CONFIRM
        input.put(GraphStateKeys.TOOL_CALLS,
                List.of(call("call-1", "executeCommand", "rm -rf /home/app")));
        input.put(GraphStateKeys.APPROVED_COMMAND, "sudo systemctl restart nginx");

        Map<String, Object> delta = verdict(input);

        assertEquals(GuardNode.VERDICT_CONFIRM, delta.get(GraphStateKeys.GUARD_VERDICT));
        assertEquals(GuardNode.VERDICT_CONFIRM, verdicts(delta).get("call-1"));
    }

    @Test
    public void confirmShouldCreateApprovalTicket() throws Exception {
        AgentApprovalService approvalService = mock(AgentApprovalService.class);
        when(approvalService.requestApproval(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(AgentApprovalEntity.builder()
                        .id("ap-1")
                        .command("sudo systemctl restart nginx")
                        .build());
        when(approvalService.buildPrompt(any())).thenReturn("需要你确认：sudo systemctl restart nginx");
        inject(node, "approvalService", approvalService);

        Map<String, Object> input = new HashMap<>();
        input.put(GraphStateKeys.TOOL_CALLS,
                List.of(call("call-1", "executeCommand", "sudo systemctl restart nginx")));
        input.put(GraphStateKeys.SESSION_ID, "s-1");
        input.put(GraphStateKeys.USER_ID, "u-1");

        Map<String, Object> delta = verdict(input);

        assertEquals(GuardNode.VERDICT_CONFIRM, delta.get(GraphStateKeys.GUARD_VERDICT));
        assertEquals("ap-1", delta.get(GraphStateKeys.APPROVAL_ID));
        assertEquals("需要你确认：sudo systemctl restart nginx", delta.get(GraphStateKeys.APPROVAL_PROMPT));
        verify(approvalService).requestApproval(eq("s-1"), eq("u-1"), anyString(), anyString(),
                anyString(), eq("sudo systemctl restart nginx"), anyString());
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field declared = type.getDeclaredField(field);
                declared.setAccessible(true);
                declared.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(field);
    }

    private Map<String, Object> verdict(Map<String, Object> input) throws Exception {
        return node.action().apply(new OverAllState(new HashMap<>(input)),
                RunnableConfig.builder().build()).get();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> verdicts(Map<String, Object> delta) {
        return (Map<String, String>) delta.get(GraphStateKeys.GUARD_VERDICTS);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> reasons(Map<String, Object> delta) {
        return (Map<String, String>) delta.get(GraphStateKeys.GUARD_REASONS);
    }

    private static AssistantMessage.ToolCall call(String id, String name, String command) {
        return new AssistantMessage.ToolCall(id, "function", name, "{\"command\":\"" + command + "\"}");
    }
}