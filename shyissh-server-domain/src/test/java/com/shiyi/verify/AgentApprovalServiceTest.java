package com.shiyi.verify;

import com.shiyi.domain.agent.adapter.repository.IAgentApprovalRepository;
import com.shiyi.domain.agent.model.entity.AgentApprovalEntity;
import com.shiyi.domain.agent.service.approval.AgentApprovalService;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 人工审批服务验证。
 * <p>
 * 这里守的是"审批不能重复生效"这条底线：同一个人点两次、两个人同时点，
 * 危险命令都只能被执行一次 —— 幂等条件压在仓储的 {@code status='PENDING'} 上，
 * 服务层只负责把"没改成"翻译成 changed=false，让上层提示"已处理"而不是报错。
 *
 * @author shiyi
 */
public class AgentApprovalServiceTest {

    private final IAgentApprovalRepository repository = mock(IAgentApprovalRepository.class);

    private final AgentApprovalService service = service();

    @Test
    public void shouldReturnNullWhenCommandBlank() {
        assertNull("空命令没有可审批的内容，不能落单",
                service.requestApproval("s-1", "u-1", "t-1", "term-1", "sshOperator", "  ", "reason"));

        verify(repository, never()).save(any());
    }

    @Test
    public void shouldPersistPendingTicket() {
        AgentApprovalEntity ticket = service.requestApproval("s-1", "u-1", "t-1", "term-1",
                "sshOperator", "  sudo systemctl restart nginx  ", "  高影响服务重启  ");

        assertNotNull(ticket);
        assertEquals(AgentApprovalEntity.STATUS_PENDING, ticket.getStatus());
        assertEquals("sudo systemctl restart nginx", ticket.getCommand());
        assertEquals("高影响服务重启", ticket.getReason());
        assertEquals("term-1", ticket.getTerminalSessionId());
        assertEquals("sshOperator", ticket.getAgentName());
        verify(repository).save(any(AgentApprovalEntity.class));
    }

    @Test
    public void shouldDegradeWhenRepositoryFails() {
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(repository).save(any(AgentApprovalEntity.class));

        assertNull("落单失败要退化成仅提示，不能把图拖挂",
                service.requestApproval("s-1", "u-1", "t-1", "term-1", "sshOperator", "rm -rf /tmp/x", ""));
    }

    @Test
    public void shouldNotChangeTwiceForSameTicket() {
        AgentApprovalEntity existing = AgentApprovalEntity.builder()
                .id("ap-1")
                .status(AgentApprovalEntity.STATUS_APPROVED)
                .command("sudo reboot")
                .build();
        when(repository.queryById("ap-1")).thenReturn(existing);
        when(repository.decide(eq("ap-1"), anyString(), anyString())).thenReturn(false);

        AgentApprovalService.DecideResult result = service.decide("ap-1", true, "u-1");

        assertFalse("已经处理过的单据必须 changed=false", result.changed());
    }

    @Test
    public void shouldApplyDecisionOnce() {
        AgentApprovalEntity existing = AgentApprovalEntity.builder()
                .id("ap-1")
                .status(AgentApprovalEntity.STATUS_PENDING)
                .command("sudo reboot")
                .build();
        when(repository.queryById("ap-1")).thenReturn(existing);
        when(repository.decide(eq("ap-1"), anyString(), anyString())).thenReturn(true);

        AgentApprovalService.DecideResult result = service.decide("ap-1", true, "u-1");

        assertTrue(result.changed());
        assertEquals(AgentApprovalEntity.STATUS_APPROVED, result.approval().getStatus());
        verify(repository).decide("ap-1", AgentApprovalEntity.STATUS_APPROVED, "u-1");
    }

    @Test
    public void shouldReturnUnknownTicketWhenIdMissing() {
        when(repository.queryById("missing")).thenReturn(null);

        assertFalse(service.decide("missing", true, "u-1").changed());
    }

    @Test
    public void shouldRenderCommandAndTicketIdInPrompt() {
        String prompt = service.buildPrompt(AgentApprovalEntity.builder()
                .id("ap-9")
                .command("sudo systemctl restart nginx")
                .reason("高影响服务重启")
                .build());

        assertTrue(prompt.contains("sudo systemctl restart nginx"));
        assertTrue(prompt.contains("ap-9"));
        assertTrue(prompt.contains("高影响服务重启"));
    }

    @Test
    public void shouldFallbackPromptWhenTicketMissing() {
        String prompt = service.buildPrompt(null);

        assertTrue(prompt.contains("需要人工确认"));
    }

    @Test
    public void shouldReturnEmptyPendingWhenRepositoryUnavailable() {
        AgentApprovalService bare = new AgentApprovalService();
        assertEquals(List.of(), bare.queryPending("s-1"));
    }

    private AgentApprovalService service() {
        AgentApprovalService target = new AgentApprovalService();
        try {
            java.lang.reflect.Field field = AgentApprovalService.class.getDeclaredField("agentApprovalRepository");
            field.setAccessible(true);
            field.set(target, repository);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return target;
    }
}