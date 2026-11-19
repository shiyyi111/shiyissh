package com.shiyi.domain.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.fastjson2.JSON;
import com.shiyi.domain.agent.adapter.archive.IToolOutputArchive;
import com.shiyi.domain.agent.graph.GraphStateKeys;
import com.shiyi.domain.agent.graph.support.GraphMessages;
import com.shiyi.domain.agent.graph.support.GraphSession;
import com.shiyi.domain.agent.graph.support.TokenStreamBridge;
import com.shiyi.domain.agent.graph.support.ToolResultEvaluator;
import com.shiyi.domain.agent.model.valobj.intent.IntentResultVO;
import com.shiyi.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import com.shiyi.domain.agent.model.valobj.intent.TaskStateVO;
import com.shiyi.domain.agent.service.IIntentService;
import com.shiyi.domain.agent.service.ILongTermMemoryService;
import com.shiyi.domain.agent.service.IPromptService;
import com.shiyi.domain.agent.service.intent.IntentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [observe] 观测与回填节点。
 * <p>
 * / {@code afterToolCallback} 对应：
 * <pre>
 *   输入：TOOL_OUTPUTS（toolCallId → 原始输出）；输出：ToolResponseMessage 回填 + 观测数据落库。
 *                             → promptService.detectAndRecordMilestone(...)
 *   SAA 版：[observe] 节点      → 同样的三个调用
 * </pre>
 * 两件事：
 * 1) 把工具结果包成 {@code ToolResponseMessage} 追加进消息历史，模型下一轮才能"看见"执行结果；
 * 2) 落观测数据（工具摘要、里程碑），复用 domain 层已有的服务。
 * <p>
 * 单独成一个节点而不是塞进 [tool]：执行和观测是两件事，
 * 观测将来可能要做成异步/批量/失败不影响主流程。
 *
 * @author shiyi
 */
@Component
public class ObserveNode implements GraphNode {

    private static final Logger log = LoggerFactory.getLogger(ObserveNode.class);

    /** 回填给模型的单条工具输出上限。 */
    private static final int MAX_TOOL_OUTPUT_CHARS = 4000;

    /**
     * 一轮内所有工具输出回填总量的上限（不是每条的）。
     * <p>
     * 一次模型调用可能并发多个工具：3 个工具各留 4000 字就是 12000 字≈6000 token，
     * 单这一轮就把整份历史预算吃光，下一轮 [enrich] 不得不把历史砍到很短。
     * 所以回填要按"轮"设总量，而不是按"条"设上限。
     */
    private static final int ROUND_TOOL_OUTPUT_CHARS = 4000;

    /** 多工具并发时的单条下限：均分后低于这个值，宁可超出总量也保证每条能看清。 */
    private static final int MIN_PER_TOOL_OUTPUT_CHARS = 800;

    /**
     * 截断时头部占比（其余留给尾部）。
     * <p>
     * SSH 场景下命令的结论大多在尾部：{@code df -h} 的容量行、{@code free -m} 的统计行、
     * 报错堆栈的最后几行、{@code tail -n 500} 的末尾。只留头部会让模型"看到一堆细节、
     * 看不到结论"，所以头尾各留一半，中间用一行说明省略了多少字符。
     */
    private static final double HEAD_RATIO = 0.5;

    /**
     * 落库原文的上限。
     * <p>
     * 落库口径与回填口径不同：回填给模型要省 token（{@link #MAX_TOOL_OUTPUT_CHARS}），
     * 落库则是复盘/评测/二次取用的依据，应尽量保留原文。但也不能无上限——
     * 一条 {@code cat} 二进制文件的输出可能有几十 MB，会把单行撑爆。
     */
    private static final int MAX_PERSIST_CHARS = 100_000;

    @Resource
    private IPromptService promptService;

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    @Resource
    private IIntentService intentService;

    /**
     * 工具输出归档器：可选依赖。
     * <p>
     * 用 {@code @Autowired(required = false)} 而不是 {@code @Resource}，是因为"归档"是增强能力：
     * 单测里直接 new 出节点、或者部署时没配归档目录，都不应该让节点创建失败。
     * 为 null 时只回填截断版（与没有归档能力时完全一致）。
     */
    @Autowired(required = false)
    private IToolOutputArchive toolOutputArchive;

    @Override
    public String name() {
        return GraphStateKeys.NODE_OBSERVE;
    }

    @Override
    public AsyncNodeActionWithConfig action() {
        return AsyncNodeActionWithConfig.node_async((OverAllState state, RunnableConfig config) -> {
            List<Message> messages = new ArrayList<>(GraphMessages.messages(state));
            List<AssistantMessage.ToolCall> toolCalls =
                    state.<List<AssistantMessage.ToolCall>>value(GraphStateKeys.TOOL_CALLS).orElse(List.of());
            String output = state.value(GraphStateKeys.TOOL_OUTPUT, "");
            String sessionId = state.value(GraphStateKeys.SESSION_ID, "");
            Map<String, String> outputs = state.value(GraphStateKeys.TOOL_OUTPUTS, Map.of());

            if (!toolCalls.isEmpty()) {
                // 一次模型调用可能有多个工具调用：全部回填到同一条 ToolResponseMessage 里，
                // 与 OpenAI / Spring AI 的 tool 消息语义一致（多个 response 对应多个 tool_call_id）。
                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                IntentResultVO reclassified = null;
                Map<String, Boolean> toolSuccess = new LinkedHashMap<>();
                boolean allSuccess = true;
                // 本轮每个工具能分到多少字符：并发多工具时按总量均分，避免"条条 4000"累加超预算
                int perToolBudget = budgetPerTool(toolCalls.size());
                for (AssistantMessage.ToolCall call : toolCalls) {
                    String rawOutput = outputs.getOrDefault(call.id(), output);
                    // 截断：一条命令的输出可能有几万行，整段塞回模型既贵又容易把关键信息淹没，
                    // 回填给模型的内容必须截断，否则一条 tail -n 500 的日志就能把上下文撑爆。
                    String toolOutput = truncateToolOutput(rawOutput, perToolBudget);
                    // 成败判定：
                    // 之前这里写死 success，导致失败被记成成功、任务态永远推进、排障记忆永不写入。
                    boolean success = ToolResultEvaluator.isSuccess(call.name(), rawOutput);
                    String hint = ToolResultEvaluator.analyzeError(call.name(), rawOutput);
                    toolSuccess.put(call.id(), success);
                    allSuccess = allSuccess && success;

                    Map<String, Object> responseBody = new LinkedHashMap<>();
                    responseBody.put("status", ToolResultEvaluator.statusOf(call.name(), rawOutput));
                    responseBody.put("stdout", toolOutput);
                    // 被截断的超长输出：全文归档到文件，只把"原有多大 + 存在哪"告诉模型。
                    // 为什么不让模型直接读这个路径：它在服务端本机，而命令是在远端主机上执行的，
                    // 模型拿到路径也读不到；归档的价值是事后复盘、评测回放、人工排查"当时到底报了什么"。
                    // 对模型有用的信息是 full_output_chars——它会据此判断"输出被截了，要不要换更精确的命令"。
                    if (rawOutput.length() > perToolBudget && toolOutputArchive != null) {
                        String archivePath = toolOutputArchive.archive(sessionId, call.id(), call.name(), rawOutput);
                        if (archivePath != null) {
                            responseBody.put("full_output_chars", rawOutput.length());
                            responseBody.put("full_output_path", archivePath);
                        }
                    }
                    if (!hint.isBlank()) {
                        // 把"为什么失败 + 该怎么办"直接回灌给模型，比让它自己猜有效得多
                        responseBody.put("suggestion", hint);
                    }
                    responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                            JSON.toJSONString(responseBody)));
                    // 落观测数据：把输出里的失败信号记成里程碑（供长期记忆）
                    recordToolObservation(sessionId, toolOutput);
                    // 工具结果落库：存**完整原文**，而不是回填给模型的截断版。
                    // 这一点很容易写错：如果连数据库里也只存 4000 字，那么被截掉的那部分
                    // 就永久消失了——事后复盘、评测集回放、"按 id 取回原文给模型看第二眼"
                    // 全都做不到。回填与落库是两种用途，口径必须分开。
                    saveToolMessageQuietly(state, sessionId, call, persistOutput(rawOutput), success);
                    // 工具执行结果事件
                    emitToolResultEvent(config, call, responseBody);
                    // 反馈回路：
                    // 工具结果看起来"意图走偏"时，让 IntentService 用候选意图重分类，
                    // 重分类结果写回 state，下一圈 [enrich]/[llm] 用的是新意图。
                    IntentResultVO feedback = handleIntentFeedback(state, toolOutput);
                    if (feedback != null) {
                        reclassified = feedback;
                    }
                }
                messages.add(ToolResponseMessage.builder().responses(responses).build());
                log.info("[observe] 回填 {} 个工具结果，消息总数={}，全部成功={}",
                        responses.size(), messages.size(), allSuccess);

                // 任务态推进。
                // 一次模型调用可能并发多个工具，只要有一个失败就不算整轮成功——宁可少推进
                // 一步，也不要把"失败的一轮"记成完成。
                updateTaskStateAfterFeedbackQuietly(sessionId, allSuccess, reclassified);

                if (reclassified != null) {
                    Map<String, Object> delta = new HashMap<>();
                    delta.put(GraphStateKeys.INTENT, reclassified.getIntent().name());
                    delta.put(GraphStateKeys.INTENT_RESULT, JSON.toJSONString(reclassified));
                    // 先把消息与清理动作放进同一个 delta，再整体返回
                    delta.put(GraphStateKeys.MESSAGES, messages);
                    delta.put(GraphStateKeys.TOOL_CALLS, List.of());
                    delta.put(GraphStateKeys.TOOL_SUCCESS, toolSuccess);
                    return delta;
                }

                Map<String, Object> successDelta = new HashMap<>();
                successDelta.put(GraphStateKeys.MESSAGES, messages);
                successDelta.put(GraphStateKeys.TOOL_CALLS, List.of());
                successDelta.put(GraphStateKeys.TOOL_SUCCESS, toolSuccess);
                return successDelta;
            }

            Map<String, Object> delta = new HashMap<>();
            delta.put(GraphStateKeys.MESSAGES, messages);
            // 清空本轮工具调用，避免条件边误判又走一次 [guard]
            delta.put(GraphStateKeys.TOOL_CALLS, List.of());
            return delta;
        });
    }

    /**
     * 同职责：
     * 用工具结果回报意图识别，失败且像"意图走偏"时用候选意图重分类。
     * <p>
     * 读 state 里的 INTENT_RESULT（JSON）取上一次意图，
     * 重分类成功时把新意图写回 state 的 INTENT。
     * 一旦重分类过（{@code reclassified=true}），IntentService 内部不再连锁重分类。
     *
     * @return 重分类结果；未触发/失败返回 null
     */
    private IntentResultVO handleIntentFeedback(OverAllState state, String toolResult) {
        String lastJson = state.value(GraphStateKeys.INTENT_RESULT, "");
        if (lastJson == null || lastJson.isBlank()) {
            return null;
        }
        IntentResultVO lastIntent;
        try {
            lastIntent = JSON.parseObject(lastJson, IntentResultVO.class);
        } catch (Exception e) {
            log.warn("[observe] 还原意图结果失败: {}", e.getMessage());
            return null;
        }
        if (lastIntent == null || lastIntent.getIntent() == null) {
            return null;
        }
        // 用 IntentService 的失败特征判定：命令失败往往意味着意图猜错了
        boolean success = toolResult != null && !toolResult.isBlank()
                && !IntentService.looksLikeIntentMismatch(toolResult);
        try {
            IntentResultVO reclassified = intentService.reportFeedback(
                    state.value(GraphStateKeys.SESSION_ID, ""), lastIntent, success, toolResult);
            if (reclassified != null && reclassified.getIntent() != null) {
                log.info("[observe] 反馈回路触发重分类: {} -> {} (conf={})",
                        lastIntent.getIntent(), reclassified.getIntent(), reclassified.getConfidence());
            }
            return reclassified;
        } catch (Exception e) {
            log.warn("[observe] 意图反馈失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 本轮每个工具输出能占多少字符。
     * <p>
     * 单工具时用满 {@link #MAX_TOOL_OUTPUT_CHARS}；多工具并发时按 {@link #ROUND_TOOL_OUTPUT_CHARS}
     * 均分，但不低于 {@link #MIN_PER_TOOL_OUTPUT_CHARS}——3 个工具时 1333 字，5 个工具时保底 800 字。
     * 宁可略超总量，也不要让每条都被切成读不出结论的碎片。
     */
    private static int budgetPerTool(int toolCount) {
        if (toolCount <= 1) {
            return MAX_TOOL_OUTPUT_CHARS;
        }
        return Math.max(MIN_PER_TOOL_OUTPUT_CHARS, ROUND_TOOL_OUTPUT_CHARS / toolCount);
    }

    /**
     * 截断过长的工具输出：头尾各留一部分，中间保留一行省略说明。
     * <p>
     * 不能只留头部 —— 命令的结论（容量汇总、错误堆栈、{@code tail} 的末尾）通常在尾部，
     * 只截头会让模型拿到"没有结论的细节"。省略说明里带上字符数，模型能据此判断
     * 是否需要再执行一条更精确的命令（例如加 {@code | tail}、{@code | grep}）。
     */
    private static String truncateToolOutput(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        int head = Math.max(1, (int) (maxChars * HEAD_RATIO));
        int tail = Math.max(0, maxChars - head);
        int omitted = text.length() - head - tail;
        String headPart = text.substring(0, head);
        if (tail <= 0) {
            return headPart + "\n...[" + omitted + " 字符已省略（超长输出请用更精确的命令，如 tail / grep）]";
        }
        String tailPart = text.substring(text.length() - tail);
        return headPart
                + "\n...[" + omitted + " 字符已省略（超长输出请用更精确的命令，如 tail / grep）]...\n"
                + tailPart;
    }

    /**
     * 落库用的输出：尽量保留原文，只在超过 {@link #MAX_PERSIST_CHARS} 时截断并标注。
     */
    private static String persistOutput(String text) {
        if (text == null || text.length() <= MAX_PERSIST_CHARS) {
            return text;
        }
        return text.substring(0, MAX_PERSIST_CHARS)
                + "\n...[落库已截断，原始长度 " + text.length() + " 字符]";
    }

    /**
     * 职责：工具结果落库失败不能影响主流程。
     * <p>
     * hostKey 从 state 取：命令是在哪台机器上执行的，产出的"环境事实/失败信号"就属于哪台机器，
     * 这个归属只能由入口解析一次、随 state 传下来，节点自己猜不出来。
     */
    private void saveToolMessageQuietly(OverAllState state, String sessionId,
                                        AssistantMessage.ToolCall call, String toolOutput, boolean success) {
        String userId = state.value(GraphStateKeys.USER_ID, "default");
        String hostKey = state.value(GraphStateKeys.HOST_KEY, "");
        try {
            longTermMemoryService.saveToolMessage(userId, sessionId, call.name(), call.id(),
                    withCommandTrace(call, toolOutput), success, hostKey);
        } catch (Exception e) {
            log.warn("[observe] 保存工具消息失败: {}", e.getMessage());
        }
    }

    /**
     * 落库时在工具输出前留一行命令回显。
     * <p>
     * 为什么只写进 DB、不改动给模型看的内容：{@code tool} 行本身不会被重新放进 prompt
     * （缺 tool_call_id 无法重放），但它落库后，下次加载历史时 {@code GraphSession}
     * 能据此还原出"曾执行命令：xxx"的助手痕迹，模型就不会把同一条命令再跑一遍。
     *
     * @param call       本次工具调用（含参数 JSON）
     * @param toolOutput 工具输出
     * @return 带命令回显的落库内容；取不到命令时原样返回输出
     */
    private String withCommandTrace(AssistantMessage.ToolCall call, String toolOutput) {
        String command = parseCommand(call);
        if (command == null || command.isBlank()) {
            return toolOutput;
        }
        return GraphSession.COMMAND_TRACE_PREFIX + command + "\n" + (toolOutput == null ? "" : toolOutput);
    }

    /** 从工具参数里取 shell 命令；非 executeCommand 或参数非法返回 null。 */
    private String parseCommand(AssistantMessage.ToolCall call) {
        if (call == null || !"executeCommand".equals(call.name()) || call.arguments() == null) {
            return null;
        }
        try {
            return JSON.parseObject(call.arguments()).getString("command");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 按本轮工具结果推进任务态：成功则前进一步，失败置 lastFeedbackFailed，重分类时替换 rootIntent。
     * <p>
     * 失败判定不重复做：上一层（{@link ToolResultEvaluator} 的 11 条错误特征）已经判过，
     * 这里直接用结论，避免两套口径打架。
     */
    private void updateTaskStateAfterFeedbackQuietly(String sessionId, boolean success, IntentResultVO reclassified) {
        if (intentService == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            TaskStateVO taskState = intentService.getTaskState(sessionId);
            if (taskState == null) {
                return;
            }
            if (reclassified != null
                    && reclassified.getIntent() != null
                    && reclassified.getIntent() != IntentTypeEnumVO.UNKNOWN
                    && reclassified.getIntent() != IntentTypeEnumVO.CONTINUE
                    && reclassified.getIntent() != IntentTypeEnumVO.CHAT) {
                taskState.setRootIntent(reclassified.getIntent());
            }
            taskState.setLastFeedbackFailed(!success);
            if (success) {
                if (taskState.getSteps() == null || taskState.getSteps().isEmpty()) {
                    taskState.setSteps(new ArrayList<>(List.of(taskState.getTaskDescription())));
                }
                if (taskState.getCurrentStepIndex() < 0) {
                    taskState.setCurrentStepIndex(0);
                }
                if (taskState.getCurrentStepIndex() >= taskState.getSteps().size() - 1) {
                    taskState.setCompleted(true);
                } else {
                    taskState.setCurrentStepIndex(taskState.getCurrentStepIndex() + 1);
                }
            }
            intentService.updateTaskState(sessionId, taskState);
        } catch (Exception e) {
            log.warn("[observe] 反馈后更新任务态失败: {}", e.getMessage());
        }
    }

    /**
     * 发 {@code tool_result} 结构化事件。
     * 前端靠它渲染"工具执行卡片"，与 {@code tool_call} 事件成对出现。
     */
    private void emitToolResultEvent(RunnableConfig config, AssistantMessage.ToolCall call,
                                     Map<String, Object> responseBody) {
        try {
            TokenStreamBridge bridge = config.metadata("tokenSink").map(TokenStreamBridge.class::cast).orElse(null);
            if (bridge == null) {
                return;
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event", GraphStateKeys.EVENT_TOOL_RESULT);
            event.put("type", GraphStateKeys.EVENT_TOOL_RESULT);
            event.put("toolCallId", call.id());
            event.put("toolName", call.name());
            event.put("content", String.valueOf(responseBody.get("stdout")));
            event.put("status", String.valueOf(responseBody.get("status")));
            bridge.emitEvent(event);
        } catch (Exception e) {
            log.warn("[observe] 发送 tool_result 事件失败: {}", e.getMessage());
        }
    }

    /**
     * 把工具输出里的失败信号记成里程碑；观测失败不能影响主流程。
     */
    private void recordToolObservation(String sessionId, String result) {
        try {
            promptService.detectAndRecordMilestone(sessionId, "tool", result);
        } catch (Exception e) {
            log.warn("[observe] 里程碑记录失败: {}", e.getMessage());
        }
    }
}

