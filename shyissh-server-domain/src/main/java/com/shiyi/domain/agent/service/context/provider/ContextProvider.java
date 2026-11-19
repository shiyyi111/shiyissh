package com.shiyi.domain.agent.service.context.provider;

import com.shiyi.domain.agent.model.valobj.enums.ContextPlacement;

import java.util.List;
import java.util.Map;

/**
 * 上下文采集器，提供者接口；SSH环境、初始任务、关键事件、工具摘要
 * <p>
 * 功能：可插拔的上下文采集组件。每个实现负责采集一类上下文
 * （终端环境/当前任务/里程碑/工具结果），由 ChatContextService
 * 按 getOrder() 顺序聚合。新增上下文来源只需新增实现类并加 @Component，
 * 无需改动任何调用方（开闭原则）。
 * <p>
 * 体系架构：
 * <pre>
 *                    ChatContextService
 *                    （注入 List<ContextProvider>，@PostConstruct 按 order 排序）
 *                          |
 *   +----------+-----------+-----------+----------+
 *   |          |           |           |          |
 *   v          v           v           v          v
 * Terminal   Task     Milestone  ToolResult      LongTermMemory     （order: 10/20/30/40）
 * State      Provider Provider   Provider        Provider
 * (SSH环境)  (初始任务) (关键事件)  (工具摘要)       (长期记忆)
 *   |          |           |           |
 *   +----------+-----------+-----------+
 *                          |
 *                          v
 *              合并 Map --> PromptContextVO
 * </pre>
 *
 * @author shiyi
 */
public interface ContextProvider {

    /** 提供者名称 */
    String getName();

    /** 执行顺序（小的先执行） */
    int getOrder();

    /** 是否启用 */
    boolean enabled();

    /**
     * 该 Provider 的上下文放在 Prompt 的哪一段。
     *
     * <p>默认 {@link ContextPlacement#EPHEMERAL_SUFFIX}：绝大多数上下文是实时状态，天然属于动态尾部。
     * 只有"会话内基本不变、用来帮模型记住任务目标"的内容（当前任务、沉淀的长期记忆）才覆写成
     * {@link ContextPlacement#STABLE_PREFIX}，以保住服务端前缀缓存的命中面。
     *
     * @return 放置位置
     */
    default ContextPlacement getPlacement() {
        return ContextPlacement.EPHEMERAL_SUFFIX;
    }

    /**
     * 采集上下文
     *
     * @param sessionId         对话会话 ID
     * @param userId            用户 ID
     * @param terminalSessionId SSH 终端会话 ID（可为 null）
     * @param messageHistory    消息历史
     * @return 上下文键值对（如 osInfo、milestoneVOS），允许为空
     */
    Map<String, Object> provide(String sessionId, String userId, String terminalSessionId, List<Map<String, Object>> messageHistory);

    /**
     * 采集上下文（带主机标识）。
     * <p>
     * 只有需要按主机收窄的提供者（长期记忆）才覆写这个方法；其余提供者沿用上面的签名。
     * 用 default 转发而不是给每个实现加参数，是为了新增维度时不必改所有实现类。
     *
     * @param hostKey 主机标识（SSH 连接 ID），可能为空
     */
    default Map<String, Object> provide(String sessionId, String userId, String terminalSessionId,
                                        String hostKey, List<Map<String, Object>> messageHistory) {
        return provide(sessionId, userId, terminalSessionId, messageHistory);
    }

}
