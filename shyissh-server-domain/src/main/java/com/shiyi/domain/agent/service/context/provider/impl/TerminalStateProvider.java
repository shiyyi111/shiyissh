package com.shiyi.domain.agent.service.context.provider.impl;

import com.shiyi.domain.agent.service.context.provider.ContextProvider;
import com.shiyi.domain.ssh.service.ISshTerminalService;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 终端状态上下文提供者（order=10，最先执行）
 * <p>
 * 功能：通过 SSH 终端实时采集远程服务器的环境信息（OS/用户/工作目录），
 * 让模型"知道自己在哪台机器上操作"。该逻辑从 PromptService 下沉至此。
 * <p>
 * 运行过程：
 * <pre>
 *   provide(sessionId, userId, terminalSessionId, history)
 *        |
 *        | terminalSessionId 为空 ? --> 返回空 Map（无终端则不采集）
 *        v
 *   safeExec(terminalSessionId, cmd)  逐条执行采集命令
 *        |
 *        +-- "uname -srm"  --> osInfo          （操作系统/架构）
 *        +-- "whoami"      --> currentUser     （当前登录用户）
 *        +-- "pwd"         --> currentDirectory（当前工作目录）
 *        |
 *        v
 *   Map{osInfo, currentUser, currentDirectory}
 *        |
 *        v
 *   ChatContextService 合并 --> PromptContextVO --> 消息前缀 [系统环境]
 * </pre>
 * 容错设计：每条命令独立 try-catch（safeExec），单条失败仅该字段留空，
 * 环境采集是"锦上添花"，绝不阻断主流程。
 *
 * @author shiyi
 */
@Component
public class TerminalStateProvider implements ContextProvider {

    /** 合并采集用的分隔标记：唯一前缀 + 唯一后缀，解析时不会和正常输出混淆。 */
    private static final String MARK_OS = "__SHIYI_OS__";
    private static final String MARK_USER = "__SHIYI_USER__";
    private static final String MARK_PWD = "__SHIYI_PWD__";
    private static final String MARK_END = "__SHIYI_END__";

    @Resource
    private ISshTerminalService sshTerminalService;

    @Override
    public String getName() {
        return "terminal-state";
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    /**
     * 提供终端环境信息上下文。
     * <p>
     * 实时采集远程服务器的 OS 信息、当前用户、工作目录。
     * <p>
     * 案例：
     * <pre>
     *   terminalSessionId = "ssh-session-001"
     *
     *   执行命令：
     *   - uname -srm → "Linux 5.15.0-91-generic x86_64"
     *   - whoami → "root"
     *   - pwd → "/var/log/nginx"
     *
     *   返回：
     *   {
     *     osInfo="Linux 5.15.0-91-generic x86_64",
     *     currentUser="root",
     *     currentDirectory="/var/log/nginx"
     *   }
     * </pre>
     * <p>
     * 容错：单条命令失败仅该字段留空，不影响其他字段。
     */
    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();

        if (terminalSessionId == null || terminalSessionId.isEmpty()) {
            return result;
        }

        String osInfo = "";
        String user = "";
        String pwd = "";

        // 三项合成一条命令，一次写入、一次等待：每轮从 3 次 SSH 往返降到 1 次。
        // 不能改成"请求入口采一次"——executeCommandAndWait 写的是同一个持久 shell 的 stdin，
        // 模型中途 cd 到别的目录后，缓存的 pwd 就是错的；每轮重采才跟得上。
        String script = "echo " + MARK_OS + "$(uname -srm)" + MARK_END + "; "
                + "echo " + MARK_USER + "$(whoami)" + MARK_END + "; "
                + "echo " + MARK_PWD + "$(pwd)" + MARK_END;
        String raw = safeExec(terminalSessionId, script);
        osInfo = extract(raw, MARK_OS);
        user = extract(raw, MARK_USER);
        pwd = extract(raw, MARK_PWD);

        // 合并采集失败（输出被截断、shell 不支持 $() 等）：退回三条独立命令，宁可慢也不要空
        if (osInfo.isEmpty() && user.isEmpty() && pwd.isEmpty()) {
            osInfo = safeExec(terminalSessionId, "uname -srm");
            user = safeExec(terminalSessionId, "whoami");
            pwd = safeExec(terminalSessionId, "pwd");
        }

        result.put("osInfo", osInfo);
        result.put("currentUser", user);
        result.put("currentDirectory", pwd);
        return result;
    }

    /**
     * 从合并命令的输出里取回一段值。
     * <p>
     * 输出里除了结果，还有终端回显的命令行（形如 {@code echo __SHIYI_OS__$(uname -srm)__SHIYI_END__}），
     * 如果直接正则匹配就会把 {@code $(uname -srm)} 当成结果，所以先把含 {@code echo } 的行滤掉。
     */
    private String extract(String raw, String marker) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder filtered = new StringBuilder();
        for (String line : raw.split("\\R")) {
            if (line.contains("echo ")) {
                continue;
            }
            filtered.append(line).append('\n');
        }
        Matcher matcher = Pattern
                .compile(Pattern.quote(marker) + "(.*?)" + Pattern.quote(MARK_END), Pattern.DOTALL)
                .matcher(filtered);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String safeExec(String terminalSessionId, String cmd) {
        try {
            String res = sshTerminalService.executeCommand(terminalSessionId, cmd);
            return res != null ? res.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
