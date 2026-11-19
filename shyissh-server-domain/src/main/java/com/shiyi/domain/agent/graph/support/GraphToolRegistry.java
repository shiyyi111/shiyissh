package com.shiyi.domain.agent.graph.support;

import com.shiyi.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.shiyi.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import com.shiyi.domain.agent.service.tool.mcp.client.TooMcpCreateService;
import com.shiyi.domain.agent.service.tool.mcp.client.factory.DefaultMcpClientFactory;
import com.shiyi.domain.agent.service.tool.skills.ToolSkillsCreateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图链路的补充工具注册表（MCP + skills）。
 * <p>
 * 的工具装配逻辑对应：
 * <pre>
 *   装配来源：yml chat-model 的 tool-mcp-list / tool-skills-list
 *                            → OpenAiChatOptions.toolCallbacks(...)
 *   SAA 版：GraphToolRegistry.extraTools() → LlmNode 每轮拼进 toolCallbacks
 * </pre>
 * 时机上是每轮现取而不是启动时一次性装配 ——
 * 这样才能配合"末轮摘工具强制收口"的裁剪策略（同一个 ToolCallback 数组每轮复用，只是取不取的问题）。
 * <p>
 * 构建过程涉及网络（SSE/stdio MCP 连接），所以只构建一次并缓存；
 * 任何异常都降级成空数组，绝不让"工具装不上"导致整轮对话失败。
 *
 * @author shiyi
 */
@Service
public class GraphToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(GraphToolRegistry.class);

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private DefaultMcpClientFactory defaultMcpClientFactory;

    @Resource
    private ToolSkillsCreateService toolSkillsCreateService;

    /** 懒加载缓存：key = yml 里 tables 的表名，value = 该表 chat-model 下装配出来的 MCP/skills 工具。 */
    private volatile Map<String, ToolCallback[]> tableTools;

    /** agentName → 表名 的反向索引（用于"只给这个 Agent 它自己表里的工具"）。 */
    private volatile Map<String, String> agentTableIndex;

    /**
     * 额外工具（MCP 服务 + 本地 skills），不含 executeCommand。
     *
     * @return 工具数组；配置为空或构建失败时返回空数组
     */
    public ToolCallback[] extraTools() {
        Map<String, ToolCallback[]> byTable = loadTableTools();
        List<ToolCallback> all = new ArrayList<>();
        for (ToolCallback[] callbacks : byTable.values()) {
            if (callbacks != null) {
                all.addAll(List.of(callbacks));
            }
        }
        return all.toArray(new ToolCallback[0]);
    }

    /**
     * 只返回指定 Agent **所属表**配置的工具。
     * <p>
     * 按表过滤：每个 Agent 只拿自己那张表声明的工具，默认不串台；
     * 图链路早期版本把所有表的工具并成一个全局数组，结果 SSH Agent 手里出现了
     * {@code baidu-search}、{@code baidu-map}——模型会拿它们当 shell 命令试，白白浪费轮次。
     * <p>
     * 找不到 agent 归属时返回空数组（宁可没有附加工具，也不给错工具）。
     *
     * @param agentName 当前 Agent 名（yml {@code agents[].name}）
     */
    public ToolCallback[] extraTools(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return extraTools();
        }
        Map<String, ToolCallback[]> byTable = loadTableTools();
        String tableKey = agentTableIndex == null ? null : agentTableIndex.get(agentName);
        if (tableKey == null) {
            // 没归到任何表：说明该 Agent 不是从 yml agents[] 来的（单测/临时 Agent），
            // 此时不提供 MCP 工具，避免无关工具干扰
            return new ToolCallback[0];
        }
        ToolCallback[] callbacks = byTable.get(tableKey);
        return callbacks == null ? new ToolCallback[0] : callbacks;
    }

    /**
     * 按工具名查找 ToolCallback（只在 Agent 所属表里找，找不到再全表兜底）。
     * <p>
     * 用途：{@code ToolNode} 执行 MCP/skills 工具时，必须调 {@code ToolCallback#call(args)}，
     * 而不是把它们当 shell 命令丢给 SSH 执行（那是早期版本的接错线，必然产出空命令）。
     *
     * @return 找到的 callback；找不到返回 null
     */
    public ToolCallback find(String toolName, String agentName) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        ToolCallback hit = findIn(extraTools(agentName), toolName);
        if (hit != null) {
            return hit;
        }
        // 兜底：有些 Agent 没在 agents[] 里登记，但工具确实配在某张表上
        return findIn(extraTools(), toolName);
    }

    private ToolCallback findIn(ToolCallback[] callbacks, String toolName) {
        if (callbacks == null) {
            return null;
        }
        for (ToolCallback callback : callbacks) {
            if (callback == null || callback.getToolDefinition() == null) {
                continue;
            }
            if (toolName.equals(callback.getToolDefinition().name())) {
                return callback;
            }
        }
        return null;
    }

    private Map<String, ToolCallback[]> loadTableTools() {
        Map<String, ToolCallback[]> local = tableTools;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (tableTools == null) {
                Map<String, ToolCallback[]> built = build();
                Map<String, String> index = buildAgentIndex();
                agentTableIndex = index;
                tableTools = built;
            }
            return tableTools;
        }
    }

    /** agentName → 表名。同名 Agent 出现在多张表时以先出现者为准。 */
    private Map<String, String> buildAgentIndex() {
        Map<String, String> index = new HashMap<>();
        Map<String, AiAgentConfigTableVO> tables = tables();
        if (tables == null) {
            return index;
        }
        for (Map.Entry<String, AiAgentConfigTableVO> entry : tables.entrySet()) {
            AiAgentConfigTableVO table = entry.getValue();
            if (table == null || table.getModule() == null || table.getModule().getAgents() == null) {
                continue;
            }
            for (AiAgentConfigTableVO.Module.Agent agent : table.getModule().getAgents()) {
                if (agent != null && agent.getName() != null) {
                    index.putIfAbsent(agent.getName(), entry.getKey());
                }
            }
        }
        return index;
    }

    private Map<String, AiAgentConfigTableVO> tables() {
        return aiAgentAutoConfigProperties == null ? null : aiAgentAutoConfigProperties.getTables();
    }

    private Map<String, ToolCallback[]> build() {
        Map<String, ToolCallback[]> result = new LinkedHashMap<>();
        try {
            Map<String, AiAgentConfigTableVO> tables = tables();
            if (tables == null || tables.isEmpty()) {
                log.info("[tools] 未配置 ai.agent.config.tables，图链路只用内置 executeCommand");
                return result;
            }
            for (Map.Entry<String, AiAgentConfigTableVO> entry : tables.entrySet()) {
                AiAgentConfigTableVO table = entry.getValue();
                if (table == null || table.getModule() == null || table.getModule().getChatModel() == null) {
                    continue;
                }
                AiAgentConfigTableVO.Module.ChatModel chatModel = table.getModule().getChatModel();
                List<ToolCallback> callbacks = new ArrayList<>();
                callbacks.addAll(buildMcpTools(chatModel.getToolMcpList()));
                callbacks.addAll(buildSkillTools(chatModel.getToolSkillsList()));
                result.put(entry.getKey(), callbacks.toArray(new ToolCallback[0]));
            }
            log.info("[tools] 图链路补充工具构建完成，表数={}，各表数量={}", result.size(),
                    result.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey, e -> e.getValue().length)));
        } catch (Exception e) {
            log.warn("[tools] 补充工具构建失败，降级为只有 executeCommand: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
        return result;
    }

    private List<ToolCallback> buildMcpTools(List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> mcpList) {
        List<ToolCallback> callbacks = new ArrayList<>();
        if (mcpList == null || mcpList.isEmpty()) {
            return callbacks;
        }
        for (AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp : mcpList) {
            try {
                TooMcpCreateService service = defaultMcpClientFactory.getTooMcpCreateService(toolMcp);
                ToolCallback[] tools = service.buildToolCallback(toolMcp);
                if (tools != null) {
                    callbacks.addAll(List.of(tools));
                }
            } catch (Exception e) {
                log.warn("[tools] MCP 工具构建失败 mcp={}: {}", toolMcp, e.getMessage());
            }
        }
        return callbacks;
    }

    private List<ToolCallback> buildSkillTools(List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> skillList) {
        List<ToolCallback> callbacks = new ArrayList<>();
        if (skillList == null || skillList.isEmpty()) {
            return callbacks;
        }
        for (AiAgentConfigTableVO.Module.ChatModel.ToolSkills skills : skillList) {
            try {
                ToolCallback[] tools = toolSkillsCreateService.buildToolCallback(skills);
                if (tools != null) {
                    callbacks.addAll(List.of(tools));
                }
            } catch (Exception e) {
                log.warn("[tools] skills 工具构建失败 skills={}: {}", skills, e.getMessage());
            }
        }
        return callbacks;
    }
}