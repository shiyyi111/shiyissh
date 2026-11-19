package com.shiyi.test;

import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 校验 ssh-agent.yml 的智能体定义结构。
 * <p>
 * 为什么值得单独测：yml 是装配的唯一入口，缩进错一位、字段名写错（subAgents vs sub-agents）
 * 都会静默退化成"单体 Agent"，运行时只表现为"模型不派发子任务"，很难排查。
 *
 * @author shiyi
 */
public class GraphAgentConfigTest {

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadAgents() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("agent/ssh-agent.yml")) {
            assertNotNull("找不到 agent/ssh-agent.yml", in);
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> table = (Map<String, Object>) dig(root, "ai", "agent", "config", "tables", "sshAgent");
            return (List<Map<String, Object>>) dig(table, "module", "agents");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void shouldDefineThreeAgentsWithInstruction() {
        List<Map<String, Object>> agents = loadAgents();
        assertEquals("应有 3 个智能体（sshOperator + 2 个子智能体）", 3, agents.size());

        for (Map<String, Object> agent : agents) {
            assertNotNull(agent.get("name"));
            Object instruction = agent.get("instruction");
            assertNotNull("每个 Agent 都必须有 instruction: " + agent.get("name"), instruction);
            assertTrue(String.valueOf(instruction).trim().length() > 20);
        }

        assertEquals("sshOperator", agents.get(0).get("name"));
        assertEquals("sshDiagnosisAgent", agents.get(1).get("name"));
        assertEquals("sshChangeAgent", agents.get(2).get("name"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldDeclareSubAgentsOnParent() {
        Map<String, Object> parent = loadAgents().get(0);
        // 写法是 sub-agents，由 Spring relaxed binding 映射到 VO 的 subAgents 字段
        Object sub = parent.get("sub-agents");
        assertNotNull("父 Agent 必须声明 sub-agents，否则不会挂 dispatchSubAgent 工具", sub);
        List<String> names = (List<String>) sub;
        assertEquals(2, names.size());
        assertEquals("sshDiagnosisAgent", names.get(0));
        assertEquals("sshChangeAgent", names.get(1));
    }

    private static Object dig(Object node, String... path) {
        Object current = node;
        for (String key : path) {
            current = ((Map<String, Object>) current).get(key);
            if (current == null) {
                return null;
            }
        }
        return current;
    }
}