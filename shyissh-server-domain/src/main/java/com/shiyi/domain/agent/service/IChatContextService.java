package com.shiyi.domain.agent.service;

import com.shiyi.domain.agent.model.valobj.prompt.PromptContextVO;

import java.util.List;
import java.util.Map;

/**
 * 上下文管理领域服务接口，负责 ReAct 对话的上下文聚合与裁剪：
 * <ul>
 *   <li>聚合各 ContextProvider 输出，组装 PromptContextVO</li>
 *   <li>在 token 预算内裁剪消息历史</li>
 * </ul>
 * 在整体架构中的位置：
 * <pre>
 *   图节点  [enrich] / [observe]
 *              |           ^
 *              v           | 富化消息 / 裁剪历史
 *   Domain 层  IChatContextService <-- PromptService（构建消息前缀）
 *              |
 *              +-- context/provider/impl/*Provider  （上下文采集：环境/任务/里程碑/工具摘要）
 *              +-- context/reducer/impl/*Reducer    （消息裁剪：优先级/滑动窗口/混合）
 * </pre>
 *
 * @author shiyi
 */
public interface IChatContextService {

    /**
     * 聚合上下文，构建 PromptContextVO
     *
     * @param sessionId         对话会话 ID
     * @param userId            用户 ID
     * @param terminalSessionId SSH 终端会话 ID（可为 null）
     * @param messageHistory    消息历史
     * @return 组装完成的动态上下文值对象
     */
    PromptContextVO buildPromptContext(String sessionId, String userId, String terminalSessionId, List<Map<String, Object>> messageHistory);

    /**
     * 聚合上下文（带主机标识）。
     * <p>
     * 主机标识只影响需要按主机收窄的提供者（长期记忆的 HOST 作用域）；
     * 不传时按 USER 级记忆召回，与老调用方行为一致。
     *
     * @param sessionId         对话会话 ID
     * @param userId            用户 ID
     * @param terminalSessionId SSH 终端会话 ID（可为 null）
     * @param hostKey           主机标识（SSH 连接 ID），可为空
     * @param messageHistory    消息历史
     * @return 组装完成的动态上下文值对象
     */
    default PromptContextVO buildPromptContext(String sessionId, String userId, String terminalSessionId,
                                               String hostKey, List<Map<String, Object>> messageHistory) {
        return buildPromptContext(sessionId, userId, terminalSessionId, messageHistory);
    }

    /**
     * 在 token 预算内裁剪消息历史
     *
     * @param history     原始消息历史
     * @param tokenBudget token 预算（<=0 时使用默认值）
     * @return 裁剪后的消息历史
     */
    List<Map<String, Object>> trimHistory(List<Map<String, Object>> history, int tokenBudget);
}

