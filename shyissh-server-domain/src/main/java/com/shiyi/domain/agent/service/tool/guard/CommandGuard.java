package com.shiyi.domain.agent.service.tool.guard;

import java.util.List;
import java.util.regex.Pattern;

/**
 * SSH 危险命令守卫。
 *
 * <p>三档语义，按"这条命令会不会改动机器"划分：</p>
 * <ul>
 *   <li>{@link Level#ALLOW} —— 只读查询，直接执行（{@code df -h}、{@code ps aux}、{@code journalctl -u}、
 *       {@code docker ps} 等）。不进任何规则表即视为只读，所以默认路径是放行，而不是默认拦截。</li>
 *   <li>{@link Level#CONFIRM} —— 会改动机器（写文件、删数据、改服务、装卸载软件、重启），
 *       但运维场景里确实可能要做：交给人工审批，批准后照常执行。</li>
 *   <li>{@link Level#BLOCK} —— 执行瞬间就不可逆地毁掉系统或数据，且没有任何合法运维用途，
 *       这条路径不存在"人批准后安全执行"的形态（如 {@code mkfs} 打分区表）。</li>
 * </ul>
 *
 * <p>结果由图里的节点消费：{@code [guard]} 做整批裁决，{@code [tool]} 逐条执行；
 * {@code CONFIRM} 的命令会落审批单，由 {@link Level#BLOCK} 之外的路径处理。</p>
 *
 * <p>纯函数、无状态，可直接单测。</p>
 *
 * @author shiyi
 */
public final class CommandGuard {

    /** 处置级别。 */
    public enum Level {
        /** 放行：只读查询。 */
        ALLOW,
        /** 需要用户确认后才能执行：会改动机器，但操作本身有合法用途。 */
        CONFIRM,
        /** 直接拒绝：执行即不可逆，且不存在合法的执行形态。 */
        BLOCK
    }

    /** 判定结果。 */
    public record Verdict(Level level, String reason) {
        public static final Verdict ALLOW = new Verdict(Level.ALLOW, "");
    }

    private record Rule(Pattern pattern, String reason) {
        Rule(String regex, String reason) {
            this(Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL), reason);
        }
    }

    /**
     * 直接拒绝：只留"命令本身就是破坏动作、不存在正常用法"的几类。
     * <p>
     * 这里刻意不放 {@code rm -rf /}、{@code shutdown}、{@code curl | sh} 这类命令 ——
     * 它们都是真实运维里会出现的动作（清理、维护重启、装软件），
     * 该由人来判断这一次要不要做，而不是被系统一刀切掉。
     */
    private static final List<Rule> BLOCK_RULES = List.of(
            new Rule(":\\s*\\(\\s*\\)\\s*\\{", "疑似 fork bomb（无限派生进程，会耗尽系统资源）"),
            new Rule("\\bdd\\s+[^|;&\\n]*\\bof=/dev/(?:sd|nvme|hd|vd|mmcblk|xvd)",
                    "向块设备写入数据，会直接损坏磁盘或分区"),
            new Rule("\\bmkfs(?:\\.[a-z0-9]+)?\\b", "格式化文件系统，会清空目标分区全部数据"),
            new Rule(">\\s*/dev/(?:sd|nvme|hd|vd|mmcblk|xvd)", "直接写块设备，会破坏分区表或文件系统"),
            new Rule("\\bkill\\s+-9\\s+1\\b|\\bkillall5\\b", "杀死 init 进程，会直接导致系统崩溃")
    );

    /**
     * 需要用户确认：会改动机器、可能造成业务影响或数据丢失，但都有合法使用场景。
     * <p>
     * 顺序即优先级：同一条命令命中多条时返回第一条的原因，
     * 所以"删除根目录""覆盖系统账户文件"这类更严重的排在同类的通用规则之前。
     */
    private static final List<Rule> CONFIRM_RULES = List.of(
            new Rule("(?:^|[\\s;&|(`])(?:sudo\\s+)?rm\\s+(?:-[a-zA-Z]+\\s+)*-[a-zA-Z]*[rR][a-zA-Z]*\\s+(?:/\\*|~|/)\\s*(?:$|[;&|`)])",
                    "递归删除根目录或家目录，基本无法恢复"),
            new Rule(">\\s*/etc/(?:passwd|shadow|sudoers|fstab|hosts)\\b",
                    "覆盖系统账户或关键配置文件，写坏可能导致无法登录"),
            new Rule("\\b(?:shutdown|reboot|poweroff|halt)\\b",
                    "关机或重启，会中断该服务器上的全部服务"),
            new Rule("\\b(?:curl|wget)\\s+[^|;&\\n]*\\|\\s*(?:sudo\\s+)?(?:ba|z|da|k)?sh\\b",
                    "从网络下载脚本并直接以 shell 执行，脚本内容不受控制"),
            new Rule("\\bchmod\\s+(?:-[a-zA-Z]*R[a-zA-Z]*\\s+)?777\\s+/(?:\\s|$|\\*)",
                    "将根目录权限改为 777，系统会失去访问控制"),
            new Rule("(?:^|[\\s;&|(`])(?:sudo\\s+)?rm\\s+(?:-[a-zA-Z]+\\s+)*-[a-zA-Z]*[rR][a-zA-Z]*\\s+\\S",
                    "递归删除目录或文件，删除后不可恢复"),
            new Rule("\\bchmod\\s+-R\\s+777\\b", "递归放开全部权限，存在越权访问风险"),
            new Rule("(?:^|[\\s;&|(`])sudo\\b", "以 root 身份执行命令，影响范围更大"),
            new Rule("\\bsystemctl\\s+(?:stop|disable|mask|kill)\\b", "停止或禁用系统服务，可能导致业务中断"),
            new Rule("\\b(?:iptables|ip6tables|ufw|firewall-cmd)\\b", "修改防火墙规则，可能导致远程连接中断"),
            new Rule(">\\s*/etc/", "写入系统配置目录"),
            new Rule("\\b(?:userdel|usermod|groupdel|passwd)\\b", "修改系统账户信息"),
            new Rule("\\b(?:apt|apt-get|yum|dnf)\\s+(?:remove|purge|autoremove|erase)\\b",
                    "卸载软件包，可能连带移除依赖"),
            new Rule("\\bdocker\\s+(?:rm\\s+-f|system\\s+prune|volume\\s+rm|rmi\\s+-f)\\b",
                    "强制删除容器或镜像，可能丢失运行状态"),
            new Rule("(?i)\\bdrop\\s+(?:database|table)\\b", "删除数据库或数据表"),
            new Rule("\\btruncate\\s+-s\\s*0\\b|>\\s*\\S+\\.log\\b", "清空文件内容"),
            new Rule("\\bmv\\s+[^|;&\\n]*\\s+/dev/null\\b", "将文件移入 /dev/null，等效于删除")
    );

    private CommandGuard() {
    }

    /**
     * 判断一条命令的处置级别。
     *
     * @param command 模型生成的原始命令，允许为空
     * @return 永不为 null；安全命令返回 {@link Verdict#ALLOW}
     */
    /** 补充拦截：kill -9 -1 会把信号广播给整个进程组（含 PID 1 的会话祖先）。 */
    private static final List<Rule> EXTRA_BLOCK_RULES = List.of(
            new Rule("\\bkill\\s+-9\\s+-1\\b", "向整个进程组发送 SIGKILL")
    );

    /** 补充审批：find ... -delete / -exec rm 是批量删除的常见写法，效果与 rm -rf 同级。 */
    private static final List<Rule> EXTRA_CONFIRM_RULES = List.of(
            new Rule("\\bfind\\s+[^|;&\\n]*\\s-(?:delete|exec\\s+rm)\\b", "批量查找并删除文件")
    );

    /**
     * 判断一条命令的处置级别。
     *
     * @param command 模型生成的原始命令，允许为空
     * @return 永不为 null；安全命令返回 {@link Verdict#ALLOW}
     */
    public static Verdict inspect(String command) {
        if (command == null || command.isBlank()) {
            return Verdict.ALLOW;
        }
        // 多行脚本合并成一行，避免换行绕过
        String normalized = command.replace('\r', ' ').replace('\n', ' ').trim();
        // 引号与反斜杠同样是绕过手段（bash -c \"rm -rf /\"、\rm -rf /、eval \"...\"），
        // 对原串和去掉引号/转义后的串各判一次，取更严重的级别。
        Verdict direct = inspectText(normalized);
        if (direct.level() == Level.BLOCK) {
            return direct;
        }
        String stripped = stripQuotesAndEscapes(normalized);
        Verdict variant = stripped.equals(normalized) ? Verdict.ALLOW : inspectText(stripped);
        if (variant.level() == Level.BLOCK) {
            return variant;
        }
        return direct.level() == Level.CONFIRM ? direct : variant;
    }

    private static Verdict inspectText(String text) {
        for (Rule rule : BLOCK_RULES) {
            if (rule.pattern().matcher(text).find()) {
                return new Verdict(Level.BLOCK, rule.reason());
            }
        }
        for (Rule rule : EXTRA_BLOCK_RULES) {
            if (rule.pattern().matcher(text).find()) {
                return new Verdict(Level.BLOCK, rule.reason());
            }
        }
        for (Rule rule : CONFIRM_RULES) {
            if (rule.pattern().matcher(text).find()) {
                return new Verdict(Level.CONFIRM, rule.reason());
            }
        }
        for (Rule rule : EXTRA_CONFIRM_RULES) {
            if (rule.pattern().matcher(text).find()) {
                return new Verdict(Level.CONFIRM, rule.reason());
            }
        }
        return Verdict.ALLOW;
    }

    /**
     * 去掉引号、反斜杠转义与多余空白，得到 shell 真正会执行的样子 的近似。
     * <p>
     * 只用于规则匹配，不做真正的 shell 解析：目的是让带引号、带反斜杠的写法
     * 退化成规则认识的样子，而不是追求语义完全等价。
     */
    private static String stripQuotesAndEscapes(String command) {
        StringBuilder sb = new StringBuilder(command.length());
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (ch == '"' || ch == '\'') {
                continue;
            }
            if (ch == '\\' && i + 1 < command.length()) {
                sb.append(command.charAt(++i));
                continue;
            }
            sb.append(ch);
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    /**
     * 便捷判断：是否必须拒绝执行。
     */
    public static boolean isBlocked(String command) {
        return inspect(command).level() == Level.BLOCK;
    }
}