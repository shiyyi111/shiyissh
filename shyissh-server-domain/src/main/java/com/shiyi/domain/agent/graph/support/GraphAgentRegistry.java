package com.shiyi.domain.agent.graph.support;

import com.shiyi.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.shiyi.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图的智能体配置注册表（读 yml 的 {@code module.agents}）。
 * <p>
 * 
 * <pre>
 *   yml agents[] → 运行期按名字取 Agent 定义（name / description / instruction / sub-agents）
 *                                           .instruction(agentConfig.getInstruction())
 *                                           .subAgents(...)
 *   SAA 版：yml agents[] → GraphAgentRegistry → [enrich] 取 instruction 拼 system
 *                                              → [llm] 按 subAgents 挂 dispatch 工具
 * </pre>
 * instruction 每轮现取而不是启动时焊死，好处是改 yml 不用重启装配。
 * <p>
 * <b>关于"取哪张表"</b>：如果只读 {@code tables} 的第一张就停，
 * 而 {@code tables} 的顺序由 yml 导入顺序决定（{@code application-dev.yml} 里
 * {@code test_agent.yml} 排在 {@code ssh-agent.yml} 前面），于是默认 Agent 变成了
 * {@code CodeWriterAgent}（"You are a Java Code Generator."），
 * {@code sshOperator} 永远查不到、静默回退 → {@code /api/agent/graph/*} 实际跑的是代码写作助手。
 * 现在改成：<b>跨全部表按名字查</b>，找不到就返回 null（由调用方决定降级），
 * 默认 Agent 显式取 {@code ai.agent.graph.default-agent}（缺省 {@code sshOperator}）。
 *
 * @author shiyi
 */
@Service
public class GraphAgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(GraphAgentRegistry.class);

    /** SSH 场景的默认 Agent 名（yml {@code ssh-agent.yml} 里 ops 主 Agent）。 */
    public static final String DEFAULT_AGENT_NAME = "sshOperator";

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    /** 默认 Agent 名，可在 yml 覆盖：{@code ai.agent.graph.default-agent}。 */
    @Value("${ai.agent.graph.default-agent:" + DEFAULT_AGENT_NAME + "}")
    private String configuredDefaultAgent = DEFAULT_AGENT_NAME;

    /**
     * 全部 Agent 定义（跨所有 table 聚合，同名以先出现的为准）。
     * <p>
     * 不再"只看第一张表"：一张表对应一个 yml 应用（ssh-agent / test_agent / demo …），
     * 图链路需要按名字跨表定位。
     */
    public List<AiAgentConfigTableVO.Module.Agent> agents() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties == null
                ? null : aiAgentAutoConfigProperties.getTables();
        if (tables == null || tables.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, AiAgentConfigTableVO.Module.Agent> merged = new LinkedHashMap<>();
        for (AiAgentConfigTableVO table : tables.values()) {
            if (table == null || table.getModule() == null || table.getModule().getAgents() == null) {
                continue;
            }
            for (AiAgentConfigTableVO.Module.Agent agent : table.getModule().getAgents()) {
                if (agent == null || agent.getName() == null) {
                    continue;
                }
                merged.putIfAbsent(agent.getName(), agent);
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 默认 Agent：优先 {@code ai.agent.graph.default-agent}（缺省 sshOperator），
     * 配的没找到才退化为第一张表的第一个 Agent。
     */
    public AiAgentConfigTableVO.Module.Agent defaultAgent() {
        AiAgentConfigTableVO.Module.Agent configured = findStrict(configuredDefaultAgent);
        if (configured != null) {
            return configured;
        }
        List<AiAgentConfigTableVO.Module.Agent> list = agents();
        if (!list.isEmpty()) {
            log.warn("[agent] 未找到默认 Agent {}，退化为第一个 Agent {}",
                    configuredDefaultAgent, list.get(0).getName());
            return list.get(0);
        }
        return null;
    }

    /**
     * 按名称查找；空白名回退默认 Agent，找不到返回 <b>null</b>（不再静默回退默认）。
     * <p>
     * 这里必须严格：{@code GraphDispatchService} 靠 null 判断"要派发的子 Agent 是否存在"，
     * 静默回退会让模型以为派发成功、实际跑错了 Agent。
     */
    public AiAgentConfigTableVO.Module.Agent find(String name) {
        if (name == null || name.isBlank()) {
            return defaultAgent();
        }
        AiAgentConfigTableVO.Module.Agent agent = findStrict(name);
        if (agent == null) {
            log.warn("[agent] yml 中不存在名为 {} 的 Agent", name);
        }
        return agent;
    }

    /** 严格按名字查，不做任何回退。 */
    private AiAgentConfigTableVO.Module.Agent findStrict(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (AiAgentConfigTableVO.Module.Agent agent : agents()) {
            if (name.equals(agent.getName())) {
                return agent;
            }
        }
        return null;
    }

    /** 某个 Agent 可派发的子 Agent；未配置或父 Agent 不存在返回空列表。 */
    public List<AiAgentConfigTableVO.Module.Agent> subAgentsOf(String name) {
        AiAgentConfigTableVO.Module.Agent parent = find(name);
        if (parent == null || parent.getSubAgents() == null || parent.getSubAgents().isEmpty()) {
            return Collections.emptyList();
        }
        List<AiAgentConfigTableVO.Module.Agent> result = new ArrayList<>();
        for (String subName : parent.getSubAgents()) {
            AiAgentConfigTableVO.Module.Agent sub = findStrict(subName);
            if (sub != null) {
                result.add(sub);
            } else {
                log.warn("[agent] {} 声明了子 Agent {}，但 yml 中不存在", name, subName);
            }
        }
        return result;
    }
}