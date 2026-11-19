package com.shiyi.domain.agent.service;

import com.shiyi.domain.agent.model.entity.ChatMessageEntity;
import com.shiyi.domain.agent.model.entity.LongTermMemoryEntity;

import java.util.List;

/**
 * 长期记忆服务接口
 * <p>
 * 定义从对话流中自动提取结构化记忆、召回相关记忆、构建 Prompt 摘要的能力。
 * 由 {@code LongTermMemoryService} 实现，在 ReAct 循环各阶段被调用：
 * <ul>
 *   <li>{@code [intent]} 节点首轮调用 {@link #recordUserMessage} 提取用户偏好</li>
 *   <li>{@code [observe]} 节点调用 {@link #recordToolObservation} 提取环境/软件/失败信号</li>
 *   <li>{@code [answer]} 节点调用 {@link #recordAssistantConclusion} 提取排查结论</li>
 *   <li>{@link LongTermMemoryProvider} 调用 {@link #buildMemorySummary} 构建注入 Prompt 的摘要</li>
 * </ul>
 *
 * @see com.shiyi.domain.agent.service.memory.LongTermMemoryService
 */
public interface ILongTermMemoryService {

    /**
     * 记录用户消息，检测并提取用户偏好记忆（USER_PREFERENCE）。
     * <p>
     * 当用户消息包含"以后""默认""记住"等偏好词时，提取为偏好记忆。
     * 意图标签会拼入关键词，提高后续召回命中率。
     *
     * @param userId      用户 ID
     * @param sessionId   会话 ID
     * @param message     用户原始消息
     * @param intentLabel 当前意图标签（可为 null）
     */
    void recordUserMessage(String userId, String sessionId, String message, String intentLabel);

    /**
     * 记录工具执行结果，自动提取环境事实、软件版本、失败信号等记忆。
     * <p>
     * 通过正则匹配从工具输出中提取：
     * <ul>
     *   <li>操作系统（ENVIRONMENT_FACT）</li>
     *   <li>软件版本（SOFTWARE_FACT，通用正则 + Redis 专用正则）</li>
     *   <li>失败信号（TROUBLESHOOTING_CASE，仅 success=false 时）</li>
     * </ul>
     *
     * @param userId        用户 ID
     * @param sessionId     会话 ID
     * @param toolName      工具名称
     * @param resultContent 工具执行结果内容
     * @param success       工具执行是否成功
     */
    void recordToolObservation(String userId, String sessionId, String toolName, String resultContent, boolean success);

    /**
     * 带主机维度的工具观察提取。
     * <p>
     * 从工具输出提取的环境事实、软件版本、失败信号天然绑定具体机器，
     * 落库时写入 scope=HOST + host_key；hostKey 为空时退化为 USER 作用域。
     *
     * @param userId        用户 ID
     * @param sessionId     会话 ID
     * @param toolName      工具名称
     * @param resultContent 工具执行结果内容
     * @param success       工具执行是否成功
     * @param hostKey       主机标识（通常为 SSH 连接 ID），可为空
     */
    void recordToolObservation(String userId, String sessionId, String toolName, String resultContent, boolean success, String hostKey);

    /**
     * 记录助手回复，当包含"结论""原因""建议"等关键词时提取排查案例记忆（TROUBLESHOOTING_CASE）。
     *
     * @param userId           用户 ID
     * @param sessionId        会话 ID
     * @param assistantContent 助手回复内容
     */
    void recordAssistantConclusion(String userId, String sessionId, String assistantContent);

    /**
     * 带主机维度的结论提取。
     * <p>
     * 排查结论往往只对某台机器成立（"根因是该机器的 /var/log 撑满"），
     * 有主机标识时按 scope=HOST 落库，否则退化为 USER 级通用经验。
     *
     * @param userId           用户 ID
     * @param sessionId        会话 ID
     * @param assistantContent 助手回复内容
     * @param hostKey          主机标识，可为空
     */
    void recordAssistantConclusion(String userId, String sessionId, String assistantContent, String hostKey);

    /**
     * 保存用户消息并提取用户偏好记忆。
     * <p>
     * 将用户消息落库（role=user，仅首轮），同时调用 {@link #recordUserMessage} 检测偏好信号。
     * Case 层通过此方法完成"消息落库 + 长期记忆提取"的闭环，不再直接调用仓储层。
     *
     * @param userId      用户 ID
     * @param sessionId   会话 ID
     * @param message     用户原始消息
     * @param intentLabel 当前意图标签（可为 null）
     * @param firstRound  是否首轮（首轮才落库 user 消息，避免多轮循环重复写入）
     */
    void saveUserMessage(String userId, String sessionId, String message, String intentLabel, boolean firstRound);

    /**
     * 保存助手回复并提取排查结论记忆。
     * <p>
     * 将助手回复落库（role=assistant），同时调用 {@link #recordAssistantConclusion} 检测结论信号。
     *
     * @param userId           用户 ID
     * @param sessionId        会话 ID
     * @param assistantContent 助手回复内容
     */
    void saveAssistantMessage(String userId, String sessionId, String assistantContent);

    /**
     * 保存助手回复，并带上模型返回的真实 token 用量。
     * <p>
     * 图链路能拿到模型 usage（{@code ChatResponse.getMetadata().getUsage()}），
     * 落库时用它代替 {@code content.length()/2} 的粗估，否则历史回读的 token 预算算不准。
     *
     * @param tokenCount 本次回答的 token 数；为空或非正数时退回长度估算
     */
    default void saveAssistantMessage(String userId, String sessionId, String assistantContent, Integer tokenCount) {
        saveAssistantMessage(userId, sessionId, assistantContent);
    }

    /**
     * 保存助手回复（带真实 token 用量与主机标识）。
     * <p>
     * 为什么需要 hostKey：最终结论通常是从"某台机器"的现象推出来的
     * （"根因是磁盘满"对另一台机器并不成立），这类结论归入 HOST 作用域才不会被错误复用；
     * 纯问答没有主机上下文时传空串，记忆退化为 USER 级。
     *
     * @param tokenCount 本次回答的 token 数；为空或非正数时退回长度估算
     * @param hostKey    主机标识（SSH 连接 ID），可为空
     */
    default void saveAssistantMessage(String userId, String sessionId, String assistantContent,
                                      Integer tokenCount, String hostKey) {
        saveAssistantMessage(userId, sessionId, assistantContent, tokenCount);
    }

    /**
     * 保存工具执行结果并提取环境/软件/失败信号记忆。
     * <p>
     * 将工具结果落库（role=tool，priority=HIGH），同时调用 {@link #recordToolObservation} 提取记忆。
     *
     * @param userId        用户 ID
     * @param sessionId     会话 ID
     * @param toolName      工具名称
     * @param toolCallId    工具调用 ID
     * @param resultContent 工具执行结果内容
     * @param success       工具执行是否成功
     */
    void saveToolMessage(String userId, String sessionId, String toolName, String toolCallId, String resultContent, boolean success);

    /**
     * 保存工具消息并提取长期记忆（带主机维度）。
     * <p>
     * 落库的 {@code chat_message} 与"记忆抽取"是两件事：前者是会话流水，
     * 后者是跨会话知识。工具观察类记忆需要主机标识才能避免跨机器污染。
     *
     * @param userId        用户 ID
     * @param sessionId     会话 ID
     * @param toolName      工具名称
     * @param toolCallId    工具调用 ID
     * @param resultContent 工具结果内容
     * @param success       是否成功
     * @param hostKey       主机标识，可为空
     */
    void saveToolMessage(String userId, String sessionId, String toolName, String toolCallId,
                         String resultContent, boolean success, String hostKey);

    /**
     * 获取指定会话最近 N 条消息（冷启动恢复上下文用）。
     * <p>
     * 图链路冷启动时（{@code GraphSession#loadHistory}）通过此方法加载历史消息，不直接调用仓储层。
     *
     * @param sessionId 会话 ID
     * @param limit     最大返回条数
     * @return 消息列表（时间正序），无数据时返回空列表
     */
    List<ChatMessageEntity> getRecentMessages(String sessionId, int limit);

    /**
     * 查询与当前对话相关的长期记忆（三池候选 + 多因子打分）。
     * <p>
     * 候选池 = 该主机的 HOST 记忆 + 跨主机的用户偏好 + 最近 60 条；
     * 打分 = 2×关键词重叠 + 类型权重 + 0.5×importance + 0.5×confidence
     *      + 0.3×时间衰减 + 0.2×log(1+hit_count)，阈值 0.6，新增主机维度重载。
     *
     * @param userId 用户 ID
     * @param query  召回查询（首条+最近用户消息拼接）
     * @param limit  最大返回条数
     * @return 相关记忆列表（按打分降序）
     */
    List<LongTermMemoryEntity> queryRelevantMemories(String userId, String query, int limit);

    /**
     * 带主机维度的记忆召回：主机事实按 hostKey 收窄，用户偏好跨主机通用。
     *
     * @param userId  用户 ID
     * @param hostKey 主机标识（通常为 SSH 连接 ID），可为空
     * @param query   召回查询
     * @param limit   最大返回条数
     * @return 相关记忆列表（按打分降序）
     */
    List<LongTermMemoryEntity> queryRelevantMemories(String userId, String hostKey, String query, int limit);

    /**
     * 构建长期记忆摘要字符串，用于注入 Prompt 的 [长期记忆] 段落。
     * <p>
     * 输出格式示例：
     * <pre>
     * - [USER_PREFERENCE] 以后执行命令都加 sudo
     * - [SOFTWARE_FACT] redis 版本为 7.0.11
     * </pre>
     *
     * @param userId 用户 ID
     * @param query  召回查询
     * @param limit  最大返回条数
     * @return 记忆摘要字符串，无记忆时返回空字符串
     */
    String buildMemorySummary(String userId, String query, int limit);

    /**
     * 写入一条由模型主动保存的记忆（记忆工具化的写入入口）。
     * <p>
     * 与自动抽取的区别：自动抽取只看工具输出里的固定特征（系统版本、失败信号），
     * 而模型在对话中会总结出"这台机器的 nginx 是编译安装的、配置在 /opt/nginx/conf"
     * 这类抽取规则覆盖不到的经验。把写入做成工具，等于把"什么值得记"的判断权
     * 交给模型，同时用 memoryKey 的哈希去重，避免同一句话被反复保存。
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID（记录记忆来源）
     * @param content   记忆内容（会被截断到 500 字符）
     * @param kind      记忆类型：PREFERENCE / ENVIRONMENT / SOFTWARE / CASE，为空按 ENVIRONMENT
     * @param hostKey   主机标识：有值写入 HOST 作用域，为空写入 USER 作用域
     */
    void saveUserMemory(String userId, String sessionId, String content, String kind, String hostKey);

    /**
     * 生成记忆摘要（带主机标识）。
     * <p>
     * 主机标识用于把 HOST 作用域的记忆限定在本机：环境事实、软件版本这类内容
     * 换一台机器就不成立，不能跨主机注入。
     *
     * @param userId  用户 ID
     * @param hostKey 主机标识（SSH 连接 ID），可为空
     * @param query   召回查询
     * @param limit   最大返回条数
     * @return 记忆摘要字符串，无记忆时返回空字符串
     */
    default String buildMemorySummary(String userId, String hostKey, String query, int limit) {
        return buildMemorySummary(userId, query, limit);
    }
}
