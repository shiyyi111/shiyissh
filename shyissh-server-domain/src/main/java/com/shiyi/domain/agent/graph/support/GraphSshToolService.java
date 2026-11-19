package com.shiyi.domain.agent.graph.support;

import com.shiyi.domain.ssh.model.entity.TerminalSessionEntity;
import com.shiyi.domain.ssh.service.ISshTerminalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 图链路的 SSH 工具服务（P2 真执行）。
 * <p>
 * 同一套"按终端会话执行 + 异常兜底"，
 * 终端会话 ID 由图 state 传进来，不依赖线程本地变量
 * （{@code GraphStateKeys.TERMINAL_SESSION_ID}），因此不需要 ThreadLocal，天然不会串号。
 * <p>
 * 没有绑定终端会话时回退到 {@link FakeSshToolService}：
 * 这样离线单测 / 演示环境（没有服务器）仍然能跑完整的图，不会因为 SSH 不可用而阻塞开发。
 *
 * @author shiyi
 */
@Service
public class GraphSshToolService {

    private static final Logger log = LoggerFactory.getLogger(GraphSshToolService.class);

    /**
     * 按终端会话分条的锁数量。
     * <p>
     * 批量并行派发（{@link GraphOrchestrator}）时多个子 Agent 会同时下发命令，
     * 但同一个 SSH 终端会话的命令必须串行执行，否则输出会互相串台
     * 并发场景下这个问题更严重）。
     * 这里用固定 64 条锁做分段，而不是 {@code ConcurrentHashMap<String, Object>}：
     * 后者每来一个会话就留一个 entry，只进不出等于内存泄漏；分段锁最多只是偶尔多等一次。
     */
    private static final int LOCK_STRIPES = 64;

    private final Object[] commandLocks = new Object[LOCK_STRIPES];

    private final ISshTerminalService sshTerminalService;

    private final FakeSshToolService fakeSshToolService;

    /**
     * 终端端口：能阻塞等待、且带超时。
     * <p>
     * 字段注入而不是构造器注入，是为了保住 {@code new GraphSshToolService(ssh, fake)} 这种单测写法；
     * 为 null 时退回 {@link ISshTerminalService#executeCommand}（其内部超时写死 30 秒）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.shiyi.domain.ssh.adapter.port.ITerminalSessionPort terminalSessionPort;

    /** 单条命令的等待上限，默认 60 秒；长任务（编译、下载）可在 yml 里调大。 */
    @org.springframework.beans.factory.annotation.Value("${app.agent.ssh.command-timeout-ms:60000}")
    private long commandTimeoutMs;

    /** 单测直接 new 时 {@code @Value} 不生效，用这个默认真兜底。 */
    private static final long DEFAULT_COMMAND_TIMEOUT_MS = 60000L;

    public GraphSshToolService(ISshTerminalService sshTerminalService, FakeSshToolService fakeSshToolService) {
        this.sshTerminalService = sshTerminalService;
        this.fakeSshToolService = fakeSshToolService;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            commandLocks[i] = new Object();
        }
    }

    /**
     * 执行命令。
     *
     * @param command           要执行的 shell 命令
     * @param terminalSessionId 终端会话 ID（空则回退假执行）
     * @return 命令输出；异常也以文本形式返回，避免把异常抛进图里打断整轮对话
     */
    /**
     * 解析该终端会话对应的主机标识（SSH 连接 ID），用于长期记忆的 HOST 作用域分组。
     * <p>
     * 为什么需要它：{@code "这台机器 Redis 是 6.2"} 这类事实只对一台机器成立，
     * 如果只按 userId 存，换一台机器就会被错误复用。主机维度必须从终端会话反查，
     * 而不是让模型自己"说"主机名——模型说的主机名不可信，且同一台机器可能有多条连接配置。
     *
     * @param terminalSessionId 终端会话 ID，可为空
     * @return 主机标识（connectionId）；无法确定时返回空串，记忆退化为 USER 级
     */
    public String resolveHostKey(String terminalSessionId) {
        if (terminalSessionId == null || terminalSessionId.isBlank()) {
            return "";
        }
        try {
            TerminalSessionEntity session = sshTerminalService.getTerminalSession(terminalSessionId);
            if (session == null || session.getConnectionId() == null) {
                return "";
            }
            return session.getConnectionId().trim();
        } catch (Exception e) {
            log.warn("[tool] 解析主机标识失败 terminalSessionId={}", terminalSessionId, e);
            return "";
        }
    }

    public String execute(String command, String terminalSessionId) {
        String cmd = command == null ? "" : command.trim();
        if (cmd.isEmpty()) {
            return "(空命令，未执行)";
        }
        if (terminalSessionId == null || terminalSessionId.isBlank()) {
            log.info("[tool] 未绑定终端会话，回退假执行: {}", cmd);
            return fakeSshToolService.execute(cmd);
        }
        try {
            if (sshTerminalService.getTerminalSession(terminalSessionId) == null) {
                log.warn("[tool] 终端会话不存在: {}", terminalSessionId);
                return "SSH 终端会话不存在或已关闭: " + terminalSessionId;
            }
            // 同一终端会话串行执行：子 Agent 可以并行"思考"，命令下发必须排队。
            synchronized (lockFor(terminalSessionId)) {
                return runWithRetry(terminalSessionId, cmd);
            }
        } catch (Exception e) {
            log.error("[tool] SSH 执行异常 command={}", cmd, e);
            return "SSH 命令执行异常: " + e.getMessage();
        }
    }

    /** 执行一次；超时按耗时给出可读提示，连接类异常重试一次。 */
    private String runWithRetry(String terminalSessionId, String cmd) {
        long timeout = commandTimeoutMs > 0 ? commandTimeoutMs : DEFAULT_COMMAND_TIMEOUT_MS;
        long start = System.currentTimeMillis();
        try {
            String output = executeOnce(terminalSessionId, cmd, timeout);
            long cost = System.currentTimeMillis() - start;
            log.info("[tool] SSH 执行完成 command={}, cost={}ms", cmd, cost);
            if (cost >= timeout) {
                return timeoutHint(timeout) + (output == null ? "" : output);
            }
            return output == null ? "" : output;
        } catch (Exception first) {
            if (!isRetryable(first)) {
                log.error("[tool] SSH 执行异常 command={}", cmd, first);
                return "SSH 命令执行异常: " + first.getMessage();
            }
            // 只重试"连接层异常"：这类失败通常发生在命令真正下发之前，重试不会重复执行写命令。
            log.warn("[tool] SSH 连接异常，重试一次 command={}, error={}", cmd, first.getMessage());
            long retryStart = System.currentTimeMillis();
            try {
                String output = executeOnce(terminalSessionId, cmd, timeout);
                long cost = System.currentTimeMillis() - retryStart;
                log.info("[tool] SSH 重试成功 command={}, cost={}ms", cmd, cost);
                if (cost >= timeout) {
                    return timeoutHint(timeout) + (output == null ? "" : output);
                }
                return output == null ? "" : output;
            } catch (Exception second) {
                log.error("[tool] SSH 重试仍失败 command={}", cmd, second);
                return "SSH 命令执行异常（已重试 1 次）: " + second.getMessage();
            }
        }
    }

    private String executeOnce(String terminalSessionId, String cmd, long timeout) {
        if (terminalSessionPort != null && terminalSessionPort.sessionExists(terminalSessionId)) {
            return terminalSessionPort.executeCommandAndWait(terminalSessionId, cmd, timeout);
        }
        return sshTerminalService.executeCommand(terminalSessionId, cmd);
    }

    /**
     * 连接层异常才重试：{@code java.io.IOException}（写流断开）或明确的连接关闭提示。
     * 超时不在此列——超时意味着命令大概率已经执行，重试会重复执行。
     */
    private boolean isRetryable(Exception exception) {
        if (exception instanceof java.io.IOException) {
            return true;
        }
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase();
        return message.contains("connection reset") || message.contains("broken pipe")
                || message.contains("not open") || message.contains("closed");
    }

    private String timeoutHint(long timeoutMs) {
        return "（命令在 " + (timeoutMs / 1000) + " 秒内未执行完，以下是已收集到的输出，可能不完整；"
                + "长任务建议显式带超时，例如「timeout 20 <命令>」，或用「nohup ... &」后台跑后再查日志）\n";
    }

    /** 取终端会话对应的分段锁。 */
    private Object lockFor(String terminalSessionId) {
        return commandLocks[Math.floorMod(terminalSessionId.hashCode(), LOCK_STRIPES)];
    }
}