package com.shiyi.config;

import com.shiyi.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.shiyi.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.Resource;
import java.util.Map;

/**
 * Agent 配置装载与启动自检。
 * <p>
 * 全量清理旧执行引擎之后，这里不再做"智能体装配"；SAA 图链路按请求从 yml 现取配置：
 * <ul>
 *   <li>{@code GraphAgentRegistry} —— 取 Agent 人格（instruction）与子智能体（sub-agents）；</li>
 *   <li>{@code GraphToolRegistry} —— 取该表下声明的 MCP 工具与 skills 工具；</li>
 *   <li>{@code GraphChatModelConfig} —— 取模型端点与模型名。</li>
 * </ul>
 * 本类只负责两件事：启动时打印装载了哪些表与 Agent，以及配置缺失时给出明确告警。
 *
 * @author shiyi
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AiAgentAutoConfigProperties.class)
public class AiAgentAutoConfig implements ApplicationListener<ApplicationReadyEvent> {

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();
        if (tables == null || tables.isEmpty()) {
            log.warn("Ai Agent 配置为空：图链路将退化为内置默认人格（FALLBACK_INSTRUCTION），且没有 MCP/skills 工具");
            return;
        }
        // 不要 JSON.toJSONString(tables.values())：整份配置里有模型 api-key、
        // 外部 MCP api_key、数据库口令，启动日志会被完整打到控制台与日志文件。
        log.info("Ai Agent 配置装载完成，共 {} 张表：{}", tables.size(), tables.keySet());

        tables.forEach((tableName, table) -> {
            if (table == null || table.getModule() == null || table.getModule().getAgents() == null) {
                return;
            }
            var agents = table.getModule().getAgents();
            log.info("  表[{}] 装载 {} 个 Agent：{}", tableName, agents.size(),
                    agents.stream().map(AiAgentConfigTableVO.Module.Agent::getName).toList());
        });
    }

}