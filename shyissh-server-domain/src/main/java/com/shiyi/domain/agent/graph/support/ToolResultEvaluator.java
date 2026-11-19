package com.shiyi.domain.agent.graph.support;

import java.util.List;
import java.util.Locale;

/**
 * 工具执行结果的成败判定与错误归因。
 * <p>
 * / {@code #analyzeError} 一一对应：
 * <pre>
 *   判定位置：工具执行完之后、回填 ToolResponseMessage 之前
 *   SAA 版：ToolResultEvaluator       → [observe] 组装 status / 落库 success / 任务态推进
 * </pre>
 * 换图之后 {@code ToolNode} 只负责"执行并拿到文本"，成败语义必须单独有归属，
 * 否则就是之前那个 bug：任何失败（permission denied、command not found）都被记成 success，
 * 连锁导致长期记忆里永远写不进 {@code TROUBLESHOOTING_CASE}，任务态也永远不会 {@code lastFeedbackFailed=true}。
 * <p>
 * 判定口径：输出里出现错误特征即失败，
 * 不引入 SSH 退出码：{@code ISshTerminalService.executeCommand} 只回传合并后的文本，没有 exit code。
 *
 * @author shiyi
 */
public final class ToolResultEvaluator {

    /** 完全相同的 11 条错误特征（小写匹配）。 */
    private static final List<String> ERROR_INDICATORS = List.of(
            "command not found",
            "no such file or directory",
            "permission denied",
            "operation not permitted",
            "cannot find",
            "error:",
            "failed",
            "fatal:",
            "unable to",
            "connection refused",
            "network is unreachable"
    );

    /** 派发类工具的失败前缀（不是 shell 输出，不能按 shell 错误特征判）。 */
    private static final String DISPATCH_FAIL_PREFIX = "（派发失败";
    private static final String BATCH_DISPATCH_FAIL_PREFIX = "（批量派发失败";

    private ToolResultEvaluator() {
    }

    /**
     * 工具调用是否成功。
     *
     * @param toolName 工具名（派发类工具走前缀判定）
     * @param output   工具回传的文本
     * @return true=成功
     */
    /** 批量派发结果的信封前缀与 完成数/总数 形态。 */
    private static final String BATCH_RESULT_PREFIX = "【批量派发结果】";
    private static final java.util.regex.Pattern BATCH_RESULT_PATTERN =
            java.util.regex.Pattern.compile("【批量派发结果】\\s*(\\d+)\\s*/");

    public static boolean isSuccess(String toolName, String output) {
        if (isDispatchTool(toolName)) {
            // 派发类工具返回的是我们自己拼的中文结论
            if (output == null) {
                return false;
            }
            if (output.startsWith(DISPATCH_FAIL_PREFIX) || output.startsWith(BATCH_DISPATCH_FAIL_PREFIX)) {
                return false;
            }
            // 批量派发跑完了但全失败时走的是正常信封：【批量派发结果】0/3 成功
            return !batchDispatchAllFailed(output);
        }
        if (output == null || output.isEmpty()) {
            // 空输出视为成功（例如 touch 这类没有 stdout 的命令）
            return true;
        }
        return !hasErrorLine(output);
    }

    /** 批量派发结果文本里 0/N 成功 视为失败。 */
    private static boolean batchDispatchAllFailed(String output) {
        if (!output.startsWith(BATCH_RESULT_PREFIX)) {
            return false;
        }
        java.util.regex.Matcher matcher = BATCH_RESULT_PATTERN.matcher(output);
        return matcher.find() && "0".equals(matcher.group(1));
    }

    /**
     * 按行找错误特征。
     * <p>
     * 为什么不再对整段做 contains：systemctl status 的成功输出里就有 Active: failed、
     * status=0 之类的行，甚至日志里会引用 failed 字样；整段匹配会把命令成功、输出里提到失败
     * 判成失败。这里逐行匹配，并放过 systemctl/journalctl 的状态行。
     */
    private static boolean hasErrorLine(String output) {
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty() || isStatusLine(line)) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            for (String indicator : ERROR_INDICATORS) {
                if (lower.contains(indicator)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** systemctl/journalctl 的状态行：服务自身状态不是命令执行失败。 */
    private static boolean isStatusLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("active:") || lower.startsWith("inactive:") || lower.startsWith("failed:")
                || lower.startsWith("loaded:") || lower.startsWith("status=") || lower.startsWith("main pid:")
                || lower.startsWith("tasks:") || lower.startsWith("memory:") || lower.startsWith("cpu:")
                || lower.startsWith("cgroup:") || lower.startsWith("since:") || lower.startsWith("●");
    }

    /**
     * 一致：按错误特征给出四类可执行建议。
     *
     * @param toolName 工具名
     * @param output   工具输出
     * @return 建议文本；成功时返回空串
     */
    public static String analyzeError(String toolName, String output) {
        if (isSuccess(toolName, output)) {
            return "";
        }
        if (isDispatchTool(toolName)) {
            return "子智能体派发失败，请检查子智能体名称是否正确、任务描述是否为空，或直接自行完成该子任务。";
        }
        String lower = output == null ? "" : output.toLowerCase(Locale.ROOT);
        if (lower.contains("command not found")) {
            return "命令不存在。可能原因：命令拼写错误、软件未安装、或命令不在 PATH 中。建议检查命令名称或安装对应软件包。";
        }
        if (lower.contains("permission denied")) {
            return "权限不足。建议使用 sudo 提升权限，或检查文件/目录权限。";
        }
        if (lower.contains("no such file or directory")) {
            return "文件或目录不存在。建议检查路径是否正确，或使用绝对路径。";
        }
        if (lower.contains("connection refused") || lower.contains("network is unreachable")) {
            return "网络连接问题。建议检查网络连接、确认目标服务是否运行、检查防火墙设置。";
        }
        return "执行失败，请检查命令和输出信息。";
    }

    /** 回填给模型的 status 字段值，与 OpenAI FunctionResponse 的 status 语义一致。 */
    public static String statusOf(String toolName, String output) {
        return isSuccess(toolName, output) ? "success" : "failed";
    }

    /**
     * 取输出的"代表行"，供历史痕迹里的单行摘要使用。
     * <p>
     * 失败时优先返回第一条含错误特征的行——那行通常就是原因（{@code command not found}、
     * {@code permission denied}）；成功时返回第一条非空行（表头或命令回显，足以让模型判断
     * "这条命令有没有产出"）。失败行优先于首行，是因为 {@code systemctl} 这类命令的首行
     * 常是 "Redirecting to /bin/systemctl..."，真正的错误在后面。
     *
     * @param output  工具输出（可含多行）
     * @param success 是否成功（{@link #isSuccess} 的结论）
     * @return 单行摘要；输出为空时返回空串
     */
    public static String keyLine(String output, boolean success) {
        if (output == null || output.isBlank()) {
            return "";
        }
        String firstNonBlank = "";
        for (String raw : output.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (firstNonBlank.isEmpty()) {
                firstNonBlank = line;
            }
            if (success) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            for (String indicator : ERROR_INDICATORS) {
                if (lower.contains(indicator)) {
                    return line;
                }
            }
        }
        return firstNonBlank;
    }

    private static boolean isDispatchTool(String toolName) {
        return "dispatchSubAgent".equals(toolName) || "dispatchSubAgents".equals(toolName);
    }
}