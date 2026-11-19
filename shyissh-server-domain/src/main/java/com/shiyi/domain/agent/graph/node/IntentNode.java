package com.shiyi.domain.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.fastjson2.JSON;
import com.shiyi.domain.agent.model.valobj.intent.IntentResultVO;
import com.shiyi.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import com.shiyi.domain.agent.model.valobj.intent.TaskStateVO;
import com.shiyi.domain.agent.service.IIntentService;
import com.shiyi.domain.agent.service.ILongTermMemoryService;
import com.shiyi.domain.agent.service.IPromptService;
import com.shiyi.domain.agent.graph.GraphStateKeys;
import com.shiyi.domain.agent.graph.support.GraphMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图识别节点。
 * <p>
 * 
 * <pre>
 *   输出：INTENT（意图名）+ INTENT_RESULT（完整 IntentResultVO 的 JSON）。
 *   SAA 版：[intent] 节点           → classifyQuietly(sessionId, userId, text)
 * </pre>
 * 调用的还是同一个 {@link IIntentService}（规则优先、LLM 兜底），
 * 只是触发时机从"插件回调"变成"图里的一个节点"。
 * <p>
 * 两点由"图"这种执行方式带来的特性：
 * <ol>
 *   <li>意图结果整条序列化成 JSON 存进 state（见 {@link GraphStateKeys#INTENT_RESULT}），
 *       由 {@code [observe]} 的反馈回路 JSON 反序列化还原，节点之间不需要共享上下文对象；</li>
 *   <li>反馈回路重分类的结果由 {@code [observe]} 直接写回 {@link GraphStateKeys#INTENT}，
 *       不必重新进入本节点，所以一轮对话里可以纠正多次意图。</li>
 * </ol>
 *
 * @author shiyi
 */
@Component
public class IntentNode implements GraphNode {

    private static final Logger log = LoggerFactory.getLogger(IntentNode.class);

    @Resource
    private IIntentService intentService;

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    @Resource
    private IPromptService promptService;

    @Override
    public String name() {
        return GraphStateKeys.NODE_INTENT;
    }

    @Override
    public AsyncNodeActionWithConfig action() {
        return AsyncNodeActionWithConfig.node_async((OverAllState state, RunnableConfig config) -> {
            List<Message> messages = GraphMessages.messages(state);
            String text = GraphMessages.lastUserText(messages);
            String sessionId = state.value(GraphStateKeys.SESSION_ID, "");
            String userId = state.value(GraphStateKeys.USER_ID, "default");

            IntentResultVO result = classifyQuietly(sessionId, userId, text);
            String intent = result.getIntent() == null ? "UNKNOWN" : result.getIntent().name();
            log.info("[intent] text={}, intent={}, confidence={}", text, intent, result.getConfidence());

            // 用户消息落库：意图节点每次请求只跑一次，因此 firstRound 恒为 true ——
            // 只把用户真正说的话写一次，避免富化后的信封文本污染记忆。
            saveUserMessageQuietly(sessionId, userId, text, intent);

            // 任务态同步：识别完意图后维护会话级 TaskState（新建 / 续接 / 完成判定）。
            // 返回非空表示"本轮应改用任务态里的 rootIntent"，CONTINUE 场景需要它：
            // 用户说"继续"时本轮意图是 CONTINUE，但真正在做的任务仍是 rootIntent。
            String effectiveIntent = syncTaskStateQuietly(sessionId, text, result);
            // 用户里程碑：把这句话交给 PromptService 判定是否任务变更 / 完成 / 纠正等关键事件。
            recordUserMilestoneQuietly(sessionId, text);

            Map<String, Object> delta = new HashMap<>();
            delta.put(GraphStateKeys.INTENT, effectiveIntent == null ? intent : effectiveIntent);
            // 反馈回路要用：整条结果（含候选意图/置信度）留给 [observe]
            delta.put(GraphStateKeys.INTENT_RESULT, toJson(result));
            return delta;
        });
    }

    private String toJson(IntentResultVO result) {
        try {
            return JSON.toJSONString(result);
        } catch (Exception e) {
            log.warn("[intent] 序列化意图结果失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 
     * 分类完成后维护 {@link TaskStateVO}，让 {@code [当前任务]} 上下文段落真正跟着进度走。
     * <p>
     * 按意图分三种情况处理：
     * <pre>
     *   CONTINUE → 有未完成任务态则把 currentStepIndex 从 -1 抬到 0、清 lastFeedbackFailed
     *   UNKNOWN / CHAT → 不动任务态
     *   其他业务意图 → 没有任务态 / 已完成 / rootIntent 变了 → 新建；否则补空字段、复位
     * </pre>
     * 节点不能改全局对象，所以这里不直接改任务态里的 currentIntent，
     * 而是把 rootIntent 名字返回给 action，由它覆盖 state 的 INTENT。
     *
     * @return 本轮实际应使用的意图名（CONTINUE 场景返回 rootIntent）；无需覆盖时返回 null
     */
    private String syncTaskStateQuietly(String sessionId, String text, IntentResultVO intentResult) {
        if (intentService == null || intentResult == null || intentResult.getIntent() == null) {
            return null;
        }
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            IntentTypeEnumVO intent = intentResult.getIntent();
            TaskStateVO taskState = intentService.getTaskState(sessionId);

            if (intent == IntentTypeEnumVO.CONTINUE) {
                if (taskState != null && !taskState.isCompleted()) {
                    if (taskState.getCurrentStepIndex() < 0) {
                        taskState.setCurrentStepIndex(0);
                    }
                    taskState.setLastFeedbackFailed(false);
                    intentService.updateTaskState(sessionId, taskState);
                    if (taskState.getRootIntent() != null) {
                        return taskState.getRootIntent().name();
                    }
                }
                return null;
            }

            if (intent == IntentTypeEnumVO.UNKNOWN || intent == IntentTypeEnumVO.CHAT) {
                return null;
            }

            boolean shouldReplace = taskState == null
                    || taskState.isCompleted()
                    || taskState.getRootIntent() != intent;
            if (shouldReplace) {
                List<String> steps = new ArrayList<>();
                steps.add(text);
                taskState = TaskStateVO.builder()
                        .taskDescription(text)
                        .rootIntent(intent)
                        .steps(steps)
                        .currentStepIndex(0)
                        .completed(false)
                        .lastFeedbackFailed(false)
                        .build();
            } else {
                if (taskState.getTaskDescription() == null || taskState.getTaskDescription().isBlank()) {
                    taskState.setTaskDescription(text);
                }
                if (taskState.getSteps() == null || taskState.getSteps().isEmpty()) {
                    taskState.setSteps(new ArrayList<>(List.of(text)));
                }
                if (taskState.getCurrentStepIndex() < 0) {
                    taskState.setCurrentStepIndex(0);
                }
                taskState.setCompleted(false);
                taskState.setLastFeedbackFailed(false);
            }
            intentService.updateTaskState(sessionId, taskState);
            return null;
        } catch (Exception e) {
            log.warn("[intent] 同步任务态失败: {}", e.getMessage());
            return null;
        }
    }

    /** 用户里程碑：命中"改一下/完成/不要"等规则才记录。 */
    private void recordUserMilestoneQuietly(String sessionId, String text) {
        if (promptService == null || text == null || text.isBlank()) {
            return;
        }
        try {
            promptService.detectAndRecordMilestone(sessionId, "user", text);
        } catch (Exception e) {
            log.warn("[intent] 记录用户里程碑失败: {}", e.getMessage());
        }
    }

    /**
     * 职责：用户消息落库失败不能影响对话，只记日志。
     */
    private void saveUserMessageQuietly(String sessionId, String userId, String text, String intent) {
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            longTermMemoryService.saveUserMessage(userId, sessionId, text, intent, true);
        } catch (Exception e) {
            log.warn("[intent] 保存用户消息失败: {}", e.getMessage());
        }
    }

    /**
     * 意图识别失败不能让整轮对话失败，返回 UNKNOWN 继续走。
     */
    private IntentResultVO classifyQuietly(String sessionId, String userId, String text) {
        if (text == null || text.isBlank()) {
            return unknown();
        }
        try {
            IntentResultVO result = intentService.classify(sessionId, userId, text);
            if (result == null || result.getIntent() == null) {
                return unknown();
            }
            return result;
        } catch (Exception e) {
            log.warn("[intent] 识别异常，降级 UNKNOWN: {}", e.getMessage());
            return unknown();
        }
    }

    private IntentResultVO unknown() {
        return IntentResultVO.builder()
                .intent(IntentTypeEnumVO.UNKNOWN)
                .confidence(0.0)
                .build();
    }
}