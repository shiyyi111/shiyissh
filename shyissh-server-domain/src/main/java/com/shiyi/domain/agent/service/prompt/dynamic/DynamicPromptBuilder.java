package com.shiyi.domain.agent.service.prompt.dynamic;

import com.shiyi.domain.agent.model.valobj.prompt.MilestoneVO;
import com.shiyi.domain.agent.model.valobj.prompt.PromptContextVO;
import com.shiyi.domain.agent.model.valobj.intent.IntentResultVO;
import com.shiyi.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 动态 Prompt 构建器
 * <p>
 * 负责将 {@link PromptContextVO} 中的环境信息、最近命令、里程碑事件
 * 翻译成模型可读的结构化文本，提供两种构建方式：
 * <ul>
 *   <li>{@link #build} —— 追加到 system instruction 末尾</li>
 *   <li>{@link #buildStableContext}/{@link #buildEphemeralContext} —— 当前主链路使用，
 *   分别渲染稳定前缀和动态尾部</li>
 * </ul>
 *
 * @author shiyi
 * 2026/7/30 23:16
 */
@Slf4j
@Component
public class DynamicPromptBuilder {

    // ==================== 信封配额 ====================
    // 五节内容都是"有用但会无限增长"的：一条很长的命令列表就能吃掉几千 token，
    // 把真正需要的对话历史挤出预算。配额不追求精确控制，只保证任何一节都不会失控。
    // 列表类一律保留最新的——旧命令、旧事件对当前排查的价值更低。

    /** [当前任务] 字符上限。 */
    private static final int QUOTA_TASK = 800;
    /** [长期记忆] 字符上限。 */
    private static final int QUOTA_STABLE_MEMORY = 600;
    /** [系统环境] 单字段字符上限（主机名、路径本来就短，超长通常是脏数据）。 */
    private static final int QUOTA_ENV_FIELD = 200;
    /** [最近执行的命令] 保留条数（从最新往回）与单条字符上限。 */
    private static final int QUOTA_COMMAND_LINES = 10;
    private static final int QUOTA_COMMAND_CHARS = 200;
    /** [关键事件] 保留条数与单条字符上限。 */
    private static final int QUOTA_MILESTONE_LINES = 12;
    private static final int QUOTA_MILESTONE_CHARS = 200;
    /** [相关记忆] 字符上限。 */
    private static final int QUOTA_RECALL = 1000;
    /** [历史会话回忆] 字符上限：只放结论，2 条足够，别把窗口挤掉。 */
    private static final int QUOTA_SESSION_RECALL = 600;
    /** [意图提示] 字符上限。 */
    private static final int QUOTA_INTENT_HINT = 200;

    /**
     * 按字符上限截断。
     * <p>
     * 截断处显式标注，避免模型把"这里没了"误读成"内容本来就这么点"，从而重复执行同一次查询。
     */
    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, max) + "…（已截断）";
    }

    /**
     * 取列表末尾若干项：最近的命令与事件对当前排查最有用，超出配额时丢最旧的。
     */
    private static <T> List<T> tail(List<T> list, int max) {
        if (list == null || list.isEmpty() || list.size() <= max) {
            return list;
        }
        return list.subList(list.size() - max, list.size());
    }

    /**
     * 将动态上下文追加到基础指令后面，拼成完整的 system instruction。
     * <p>
     * 适用于运行期可修改 system instruction 的场景。
     *
     * @param baseInstruction 基础系统指令文本
     * @param ctx             动态上下文，为 null 时直接返回基础指令
     * @return 拼接了环境信息、最近命令、里程碑事件的完整指令
     */
    public String build(String baseInstruction, PromptContextVO ctx) {
        if (ctx == null) {
            return baseInstruction;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(baseInstruction);

        appendEnvironmentInfo(sb, ctx);
        appendTaskDescription(sb, ctx);
        appendRecentCommands(sb, ctx);
        appendMilestones(sb, ctx);
        appendLongTermMemorySummary(sb, ctx);
        appendIntentLabel(sb, ctx);

        String result = sb.toString();
        log.debug("动态 Prompt 构建完成，长度: {} (基础: {}, 动态: {})",
                result.length(), baseInstruction.length(), result.length() - baseInstruction.length());
        return result;
    }

    /**
     * 将动态上下文构建为用户消息后缀（注入到用户消息后面，缓存友好）。
     * <p>
     * 适用于无法直接修改 system instruction 的场景——system instruction 在装配阶段
     * 就固定了，运行期改不了，因此把动态上下文拼在用户消息后面（保证前缀稳定，利于 Prompt Cache）。
     * <p>
     * 三类上下文"有才拼、没有不拼"：第一轮对话无历史时返回空串，不塞空标题浪费 token。
     * <p>
     * 案例 1：有环境信息和里程碑
     * <pre>
     *   ctx = PromptContextVO {
     *     serverInfo="192.168.1.100",
     *     osInfo="Linux 5.15.0",
     *     currentUser="root",
     *     currentDirectory="/var/log/nginx",
     *     milestoneVOS=[{type=ERROR, content="permission denied"}],
     *     intentLabel="DIAGNOSE"
     *   }
     *
     *   返回：
     *   "[系统环境]\n服务器: 192.168.1.100\n系统: Linux 5.15.0\n用户: root\n目录: /var/log/nginx\n\n[关键事件]\n- [ERROR] permission denied\n\n[用户意图]\nDIAGNOSE\n"
     * </pre>
     * <p>
     * 案例 2：无动态信息（返回空串）
     * <pre>
     *   ctx = PromptContextVO {}  // 所有字段为空
     *
     *   返回：""  // 不返回空标题，避免浪费 token
     * </pre>
     * <p>
     * 缓存友好设计：动态上下文放在用户消息末尾，保证 system instruction + 历史消息 +
     * 用户原始消息 这个前缀序列逐字节稳定，使 LLM Prompt Cache 能命中前面的稳定部分。
     */
    public String buildMessageSuffix(PromptContextVO ctx) {
        if (ctx == null) return "";

        String stableContext = buildStableContext(ctx);
        String ephemeralContext = buildEphemeralContext(ctx);
        if (stableContext.isEmpty()) {
            return ephemeralContext;
        }
        if (ephemeralContext.isEmpty()) {
            return stableContext;
        }
        return stableContext + "\n\n" + ephemeralContext;
    }

    /**
     * 构建稳定上下文前缀。
     * <p>
     * 只放置低频变化的内容：任务目标、用户级长期记忆。实时工具输出和 cwd 等字段
     * 不应进入这一层，否则会造成跨轮 Prompt 前缀频繁变化。
     */
    public String buildStableContext(PromptContextVO ctx) {
        if (ctx == null) return "";

        String taskDescription = stringValue(ctx.getStableContext(), "taskDescription", ctx.getTaskDescription());
        String longTermMemorySummary = stringValue(ctx.getStableContext(), "longTermMemorySummary", ctx.getLongTermMemorySummary());

        StringBuilder sb = new StringBuilder();
        boolean hasContent = false;

        if (!isEmpty(taskDescription)) {
            sb.append("[当前任务]\n").append(clip(taskDescription, QUOTA_TASK)).append("\n");
            hasContent = true;
        }

        if (!isEmpty(longTermMemorySummary)) {
            if (hasContent) sb.append("\n");
            sb.append("[长期记忆]\n").append(clip(longTermMemorySummary, QUOTA_STABLE_MEMORY)).append("\n");
            hasContent = true;
        }

        if (!hasContent) return "";
        return sb.toString();
    }

    /**
     * 构建动态上下文尾部。
     * <p>
     * 放置每轮可能变化的内容，保证稳定前缀之后的上下文只在尾部演进。
     */
    public String buildEphemeralContext(PromptContextVO ctx) {
        if (ctx == null) return "";

        Map<String, Object> context = ctx.getEphemeralContext();
        String serverInfo = stringValue(context, "serverInfo", ctx.getServerInfo());
        String osInfo = stringValue(context, "osInfo", ctx.getOsInfo());
        String currentUser = stringValue(context, "currentUser", ctx.getCurrentUser());
        String currentDirectory = stringValue(context, "currentDirectory", ctx.getCurrentDirectory());
        List<MilestoneVO> milestoneVOS = milestones(context, ctx.getMilestoneVOS());

        StringBuilder sb = new StringBuilder();
        boolean hasContent = false;

        if (!isEmpty(serverInfo) || !isEmpty(osInfo)
                || !isEmpty(currentUser) || !isEmpty(currentDirectory)) {
            sb.append("[系统环境]\n");
            if (!isEmpty(serverInfo)) sb.append("服务器: ").append(clip(serverInfo, QUOTA_ENV_FIELD)).append("\n");
            if (!isEmpty(osInfo)) sb.append("系统: ").append(clip(osInfo, QUOTA_ENV_FIELD)).append("\n");
            if (!isEmpty(currentUser)) sb.append("用户: ").append(clip(currentUser, QUOTA_ENV_FIELD)).append("\n");
            if (!isEmpty(currentDirectory)) sb.append("目录: ").append(clip(currentDirectory, QUOTA_ENV_FIELD)).append("\n");
            hasContent = true;
        }

        if (ctx.getRecentCommands() != null && !ctx.getRecentCommands().isEmpty()) {
            if (hasContent) sb.append("\n");
            sb.append("[最近执行的命令]\n");
            for (String cmd : tail(ctx.getRecentCommands(), QUOTA_COMMAND_LINES)) {
                sb.append("- ").append(clip(cmd, QUOTA_COMMAND_CHARS)).append("\n");
            }
            hasContent = true;
        }

        if (milestoneVOS != null && !milestoneVOS.isEmpty()) {
            if (hasContent) sb.append("\n");
            sb.append("[关键事件]\n");
            for (MilestoneVO milestone : tail(milestoneVOS, QUOTA_MILESTONE_LINES)) {
                sb.append("- [").append(milestone.getType().name()).append("] ")
                        .append(clip(milestone.getContent(), QUOTA_MILESTONE_CHARS)).append("\n");
            }
            hasContent = true;
        }

        // 本轮相关召回：每轮随用户输入变化，属于典型动态尾部内容
        Object recallValue = context.get("longTermRecallSummary");
        String longTermRecallSummary = recallValue instanceof String value ? value : "";
        if (!isEmpty(longTermRecallSummary)) {
            if (hasContent) sb.append("\n");
            sb.append("[相关记忆]\n").append(clip(longTermRecallSummary, QUOTA_RECALL)).append("\n");
            hasContent = true;
        }

        // 跨会话回忆：别的事故/别的会话里得出的结论，只在相关时才带进来
        Object sessionRecallValue = context.get("sessionRecallSummary");
        String sessionRecall = sessionRecallValue instanceof String value ? value : "";
        if (!isEmpty(sessionRecall)) {
            if (hasContent) sb.append("\n");
            sb.append("[历史会话回忆]\n").append(clip(sessionRecall, QUOTA_SESSION_RECALL)).append("\n");
            hasContent = true;
        }

        String intentHint = buildIntentHint(ctx);
        if (!intentHint.isEmpty()) {
            if (hasContent) sb.append("\n");
            sb.append(clip(intentHint, QUOTA_INTENT_HINT));
            hasContent = true;
        }

        return hasContent ? sb.toString() : "";
    }

    private String buildIntentHint(PromptContextVO ctx) {
        IntentResultVO result = ctx.getIntentResult();
        if (result != null && result.getIntent() == IntentTypeEnumVO.UNKNOWN && result.getConfidence() < 0.5) {
            return "";
        }

        String primary = result != null && result.getIntent() != null
                ? result.getIntent().name()
                : ctx.getIntentLabel();
        if (isEmpty(primary)) {
            return "";
        }

        StringBuilder sb = new StringBuilder("[意图提示]\n")
                .append("- 主要意图: ").append(primary);
        if (result != null) {
            sb.append(" (置信度: ").append(Math.round(result.getConfidence() * 100)).append("%)");
        }
        sb.append("\n");

        if (result != null && result.getCandidateIntents() != null && !result.getCandidateIntents().isEmpty()) {
            sb.append("- 备选: ").append(result.getCandidateIntents().stream()
                    .map(IntentTypeEnumVO::name)
                    .collect(java.util.stream.Collectors.joining(", "))).append("\n");
        }
        if (result != null && result.getEntities() != null && !result.getEntities().isEmpty()) {
            sb.append("- 实体: ").append(result.getEntities().entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(", "))).append("\n");
        }
        sb.append("- 使用原则: 该结果仅作参考，不得覆盖用户消息、历史结论和工具证据。\n");
        log.info("意图提示: {}, confidence: {}", primary, result == null ? "n/a" : result.getConfidence());
        return sb.toString();
    }

    /**
     * 追加当前任务描述段落（2-8 增强：优先从 TaskStateVO 获取，比从消息历史推断更准确）
     */
    private void appendTaskDescription(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getTaskDescription())) {
            return;
        }
        sb.append("\n\n## 当前任务\n");
        sb.append(ctx.getTaskDescription()).append("\n");
    }

    /**
     * 追加长期记忆段落（2-8 新增）。
     * <p>
     * 将 LongTermMemoryProvider 召回的长期记忆摘要渲染为 [长期记忆] 段落，
     * 拼到用户消息后面，让主模型感知用户偏好、环境信息、软件版本、排查经验等。
     */
    private void appendLongTermMemorySummary(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getLongTermMemorySummary())) {
            return;
        }
        sb.append("\n\n## 长期记忆\n");
        sb.append(ctx.getLongTermMemorySummary()).append("\n");
    }

    /**
     * 追加用户意图标签段落
     */
    private void appendIntentLabel(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getIntentLabel())) {
            return;
        }
        sb.append("\n\n## 用户意图\n");
        sb.append(ctx.getIntentLabel()).append("\n");
    }

    /**
     * 将环境信息以 Markdown 格式追加到 StringBuilder 中。
     * <p>
     * 服务器、操作系统、当前用户、工作目录四个字段全为空时跳过，不输出空标题。
     *
     * @param sb  目标 StringBuilder
     * @param ctx 动态上下文
     */
    private void appendEnvironmentInfo(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getServerInfo()) && isEmpty(ctx.getOsInfo())
                && isEmpty(ctx.getCurrentUser()) && isEmpty(ctx.getCurrentDirectory())) {
            return;
        }
        sb.append("\n\n## 当前环境信息\n");
        if (!isEmpty(ctx.getServerInfo())) sb.append("- 服务器: ").append(ctx.getServerInfo()).append("\n");
        if (!isEmpty(ctx.getOsInfo())) sb.append("- 操作系统: ").append(ctx.getOsInfo()).append("\n");
        if (!isEmpty(ctx.getCurrentUser())) sb.append("- 当前用户: ").append(ctx.getCurrentUser()).append("\n");
        if (!isEmpty(ctx.getCurrentDirectory()))
            sb.append("- 工作目录: ").append(ctx.getCurrentDirectory()).append("\n");
    }

    /**
     * 将最近执行的命令列表以 Markdown 列表格式追加到 StringBuilder 中。
     * <p>
     * 命令列表为 null 或空时跳过。
     *
     * @param sb  目标 StringBuilder
     * @param ctx 动态上下文
     */
    private void appendRecentCommands(StringBuilder sb, PromptContextVO ctx) {
        if (ctx.getRecentCommands() == null || ctx.getRecentCommands().isEmpty()) return;
        sb.append("\n## 最近操作记录\n");
        for (String cmd : ctx.getRecentCommands()) {
            sb.append("- ").append(cmd).append("\n");
        }
    }

    /**
     * 将里程碑事件列表以 Markdown 列表格式追加到 StringBuilder 中。
     * <p>
     * 每条里程碑格式为：{@code [TYPE] content}
     * <p>
     * 案例：
     * <pre>
     *   ctx.getMilestoneVOS() = [
     *     { type=ERROR, content="permission denied" },
     *     { type=TASK_CHANGE, content="换个思路看 access.log" }
     *   ]
     *
     *   追加结果：
     *   "\n## 关键事件\n- [ERROR] permission denied\n- [TASK_CHANGE] 换个思路看 access.log\n"
     * </pre>
     *
     * @param sb  目标 StringBuilder
     * @param ctx 动态上下文
     */
    private void appendMilestones(StringBuilder sb, PromptContextVO ctx) {
        if (ctx.getMilestoneVOS() == null || ctx.getMilestoneVOS().isEmpty()) return;
        sb.append("\n## 关键事件\n");
        for (MilestoneVO m : ctx.getMilestoneVOS()) {
            sb.append("- [").append(m.getType().name()).append("] ").append(m.getContent()).append("\n");
        }
    }

    /**
     * 判断字符串是否为 null 或空白。
     *
     * @param s 待检查字符串
     * @return true 表示为 null 或全空白
     */
    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String stringValue(Map<String, Object> context, String key, String fallback) {
        Object value = context == null ? null : context.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private List<MilestoneVO> milestones(Map<String, Object> context, List<MilestoneVO> fallback) {
        Object value = context == null ? null : context.get("milestoneVOS");
        if (value instanceof List<?> list) {
            return (List<MilestoneVO>) list;
        }
        return fallback;
    }
}

