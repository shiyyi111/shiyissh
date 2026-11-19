package com.shiyi.test;

import com.shiyi.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.shiyi.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import org.junit.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证 yml 的 {@code sub-agents} 能被 Spring relaxed binding 正确绑到 VO 的 {@code subAgents}。
 * <p>
 * 为什么单独测：{@link com.shiyi.domain.agent.graph.support.GraphDispatchService} 是否可用，
 * 取决于 {@link com.shiyi.domain.agent.graph.support.GraphAgentRegistry#subAgentsOf(String)}
 * 能不能读到值；如果绑定失败，运行时只表现为"模型没有派发工具可选"，不会报任何错。
 *
 * @author shiyi
 */
public class GraphAgentPropertiesBindTest {

    @Test
    public void shouldBindSubAgentsFromYaml() throws Exception {
        AiAgentAutoConfigProperties properties = bind();

        assertNotNull(properties.getTables());
        AiAgentConfigTableVO table = properties.getTables().get("sshAgent");
        assertNotNull("未绑定到 sshAgent 表", table);
        assertNotNull(table.getModule());
        assertNotNull(table.getModule().getChatModel());
        assertEquals("deepseek-v4.1-flash", table.getModule().getChatModel().getModel());

        List<AiAgentConfigTableVO.Module.Agent> agents = table.getModule().getAgents();
        assertNotNull(agents);
        assertEquals(3, agents.size());

        AiAgentConfigTableVO.Module.Agent parent = agents.get(0);
        assertEquals("sshOperator", parent.getName());
        assertNotNull("父 Agent 的 instruction 未绑定", parent.getInstruction());
        assertNotNull("sub-agents 未被绑定到 subAgents 字段", parent.getSubAgents());
        assertEquals(2, parent.getSubAgents().size());
        assertEquals("sshDiagnosisAgent", parent.getSubAgents().get(0));
        assertEquals("sshChangeAgent", parent.getSubAgents().get(1));

        assertTrue(agents.get(1).getInstruction().contains("只读诊断"));
        assertTrue(agents.get(2).getInstruction().contains("变更执行"));
    }

    private AiAgentAutoConfigProperties bind() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources =
                loader.load("ssh-agent", new ClassPathResource("agent/ssh-agent.yml"));

        StandardEnvironment environment = new StandardEnvironment();
        // 后加载的优先级更低，这里逐个 addFirst 让 yml 覆盖默认值
        for (int i = sources.size() - 1; i >= 0; i--) {
            environment.getPropertySources().addFirst(sources.get(i));
        }

        return Binder.get(environment)
                .bind("ai.agent.config", Bindable.of(AiAgentAutoConfigProperties.class))
                .orElseThrow(() -> new IllegalStateException("ai.agent.config 绑定失败"));
    }
}