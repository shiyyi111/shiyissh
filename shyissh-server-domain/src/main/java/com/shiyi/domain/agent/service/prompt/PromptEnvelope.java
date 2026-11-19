package com.shiyi.domain.agent.service.prompt;

/**
 * Prompt 信封工具类：用 XML 风格标签将一段完整 Prompt 切分为三个语义层，
 * 保证「稳定前缀 → 用户原文 → 动态后缀」的结构不因用户消息内容而断裂。
 *
 * <p>解决的问题：
 * <ul>
 *   <li>旧实现用 {@code \n---\n} 做分隔符，用户消息中包含 Markdown 分割线时会被误切；</li>
 *   <li>动态上下文（环境快照、工具摘要）被拼进用户消息后写入 Session，跨轮重复累积导致上下文膨胀；</li>
 *   <li>Session 无法区分「用户原文」与「动态后缀」，TaskProvider 取到的任务描述是拼接结果。</li>
 * </ul>
 *
 * <p>组合后的 Prompt 结构示例：
 * <pre>{@code
 * <shiyissh-stable-context>
 * [当前任务] 排查 nginx 502\n * [用户偏好] 回复用中文
 * </shiyissh-stable-context>
 *
 * <shiyissh-user-message>
 * 帮我看下 nginx 为什么 502，检查配置和日志
 * </shiyissh-user-message>
 *
 * <shiyissh-dynamic-context>
 * [系统环境] Ubuntu 22.04 / root / /etc/nginx
 * [意图提示] 诊断(DIAGNOSE, 置信度 0.87)
 * [工具摘要] cat /var/log/nginx/error.log → upstream timed out
 * </shiyissh-dynamic-context>
 * }</pre>
 *
 * <p>数据流向：
 * <ol>
 *   <li>{@link #compose} 在每次调用模型前执行，将 Provider 分层结果组装为上述结构，送入 旧执行引擎；</li>
 *   <li>{@link #extractUserMessage} 在 Session 持久化前调用，只提取用户原文（不包含 stable / dynamic 段），
 *       因此下一轮 {@code TaskProvider} 从 Session 读取历史消息时拿到的是干净的用户原文；</li>
 *   <li>如果 stable 和 dynamic 均为空，{@code compose} 直接返回用户原文，不额外增加标签开销。</li>
 * </ol>
 */
public final class PromptEnvelope {

    public static final String USER_MESSAGE_START = "<shiyissh-user-message>";
    public static final String USER_MESSAGE_END = "</shiyissh-user-message>";

    private static final String STABLE_START = "<shiyissh-stable-context>";
    private static final String STABLE_END = "</shiyissh-stable-context>";
    private static final String DYNAMIC_START = "<shiyissh-dynamic-context>";
    private static final String DYNAMIC_END = "</shiyissh-dynamic-context>";

    private PromptEnvelope() {
    }

    /**
     * 将稳定前缀、用户原文和动态后缀组装为一段结构化 Prompt。
     *
     * <p>调用示例：
     * <pre>{@code
     * String prompt = PromptEnvelope.compose(
     *     "[当前任务] 排查 nginx 502\n[用户偏好] 回复用中文",
     *     "帮我看下 nginx 为什么 502",
     *     "[系统环境] Ubuntu 22.04\n[意图提示] 诊断(0.87)"
     * );
     * }</pre>
     *
     * <p>输出：
     * <pre>{@code
     * <shiyissh-stable-context>
     * [当前任务] 排查 nginx 502\n     * [用户偏好] 回复用中文
     * </shiyissh-stable-context>
     *
     * <shiyissh-user-message>
     * 帮我看下 nginx 为什么 502
     * </shiyissh-user-message>
     *
     * <shiyissh-dynamic-context>
     * [系统环境] Ubuntu 22.04\n     * [意图提示] 诊断(0.87)
     * </shiyissh-dynamic-context>
     * }</pre>
     *
     * <p>当 stableContext 和 dynamicContext 均为 null / 空字符串时，直接返回 userMessage，不添加任何标签。
     *
     * @param stableContext  稳定前缀（TaskProvider、LongTermMemoryProvider 等输出），可为 null
     * @param userMessage    用户原始消息，原样保留不 trim
     * @param dynamicContext 动态后缀（TerminalStateProvider、ToolResultProvider、意图提示等输出），可为 null
     * @return 组装后的完整 Prompt
     */
    public static String compose(String stableContext, String userMessage, String dynamicContext) {
        // 幂等保护：入参已经是带信封的富化文本时，直接原样返回，不再二次包裹。
        // 图链路的 observe -> enrich 回边每绕一圈都会重新调用本方法，
        // 若不加这道判断，标签会一层套一层（第 2 圈 2 层、第 3 圈 3 层…），Prompt 体积指数膨胀。
        if (userMessage != null && userMessage.contains(STABLE_START)) {
            return userMessage;
        }
        String stable = normalize(stableContext);
        String dynamic = normalize(dynamicContext);
        if (stable.isEmpty() && dynamic.isEmpty()) {
            return userMessage;
        }

        StringBuilder prompt = new StringBuilder();
        if (!stable.isEmpty()) {
            prompt.append(STABLE_START).append('\n')
                    .append(stable).append('\n')
                    .append(STABLE_END).append("\n\n");
        }
        prompt.append(USER_MESSAGE_START).append('\n')
                .append(userMessage).append('\n')
                .append(USER_MESSAGE_END);
        if (!dynamic.isEmpty()) {
            prompt.append("\n\n").append(DYNAMIC_START).append('\n')
                    .append(dynamic).append('\n')
                    .append(DYNAMIC_END);
        }
        return prompt.toString();
    }

    /**
     * 从一段可能包含信封标签的 Prompt 中提取用户原文。
     *
     * <p>优先匹配 {@code <shiyissh-user-message>} 标签；如果未匹配到（例如历史数据是旧格式，
     * 使用 {@code \n---\n} 做分隔），则回退到旧分隔符切割；如果两者都不存在，原样返回。
     *
     * <p>调用示例：
     * <pre>{@code
     * // 新格式：返回 "帮我看下 nginx 为什么 502"
     * String user = PromptEnvelope.extractUserMessage(
     *     "<shiyissh-stable-context>\n...\n</shiyissh-stable-context>\n\n"
     *     + "<shiyissh-user-message>\n帮我看下 nginx 为什么 502\n</shiyissh-user-message>\n\n"
     *     + "<shiyissh-dynamic-context>\n...\n</shiyissh-dynamic-context>");
     *
     * // 旧格式兼容：返回 "原始消息"
     * String legacy = PromptEnvelope.extractUserMessage("原始消息\n---\n[动态上下文]");
     *
     * // 纯文本：原样返回
     * String plain = PromptEnvelope.extractUserMessage("just a plain message");
     * }</pre>
     *
     * <p>在消息落库前（{@code [intent]} / {@code [answer]} 节点的保存动作）调用此方法，确保写入历史的是
     * 干净的用户原文而不是拼接后的 Prompt，避免下一轮读取历史时动态上下文重复累积。
     *
     * @param prompt 可能包含信封标签的完整 Prompt，也可以是纯用户消息或旧格式拼接消息
     * @return 提取出的用户原文；如果输入为 null 或空白则原样返回
     */
    public static String extractUserMessage(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return prompt;
        }

        String extracted = extractByTag(prompt, USER_MESSAGE_START, USER_MESSAGE_END);
        if (extracted != null) {
            return extracted;
        }
        if (prompt.contains("\n---\n")) {
            return prompt.split("\\n---\\n", 2)[0].trim();
        }
        return prompt;
    }

    /**
     * 按标签对提取中间内容；标签不存在或闭合位置非法时返回 null。
     *
     * @param prompt   待解析文本
     * @param startTag 起始标签
     * @param endTag   结束标签
     * @return 标签之间的内容（已 trim），未匹配返回 null
     */
    private static String extractByTag(String prompt, String startTag, String endTag) {
        int startIndex = prompt.indexOf(startTag);
        if (startIndex < 0) {
            return null;
        }
        int contentStart = startIndex + startTag.length();
        int endIndex = prompt.lastIndexOf(endTag);
        if (endIndex <= contentStart) {
            return null;
        }
        return prompt.substring(contentStart, endIndex).trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
