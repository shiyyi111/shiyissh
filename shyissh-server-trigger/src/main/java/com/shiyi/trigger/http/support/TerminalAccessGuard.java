package com.shiyi.trigger.http.support;

import com.shiyi.domain.ssh.model.entity.SshConnectionEntity;
import com.shiyi.domain.ssh.model.entity.TerminalSessionEntity;
import com.shiyi.domain.ssh.service.ISshConnectionService;
import com.shiyi.domain.ssh.service.ISshTerminalService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 终端归属校验。
 * <p>
 * 终端接口只用 sessionId 定位一条 SSH 连接，之前任何拿到 sessionId 的调用方都能执行命令
 * （典型 IDOR：换一个 sessionId 就能操作别人的机器）。这里补一层"会话 → 连接 → 归属"的反查。
 * <p>
 * <b>安全策略</b>：请求里必须带 {@code userId}，没带直接拒绝。
 * 之前"没带就跳过校验"等于把"知道 sessionId 就能操作别人机器"的洞留着，现在默认关闭这条兼容通道。
 *
 * @author shiyi
 */
@Component
@Slf4j
public class TerminalAccessGuard {

    @Resource
    private ISshTerminalService sshTerminalService;

    @Resource
    private ISshConnectionService sshConnectionService;

    /**
     * 打开终端前校验连接归属。
     *
     * @param connectionId 连接 ID
     * @param userId       操作者（可为空，为空跳过校验）
     * @return null 表示通过；否则是可直接返回给调用方的拒绝原因
     */
    public String denyReasonByConnection(String connectionId, String userId) {
        return deny(connectionId, userId);
    }

    /**
     * 已打开的终端会话：按 sessionId 反查连接归属。
     *
     * @param sessionId 终端会话 ID
     * @param userId    操作者（可为空，为空跳过校验）
     * @return null 表示通过；否则是可直接返回给调用方的拒绝原因
     */
    public String denyReasonBySession(String sessionId, String userId) {
        if (isBlank(userId)) {
            return "缺少 userId，拒绝操作该终端会话";
        }
        if (isBlank(sessionId)) {
            return "缺少 sessionId";
        }
        TerminalSessionEntity session = sshTerminalService.getTerminalSession(sessionId);
        if (session == null) {
            return "终端会话不存在或已关闭";
        }
        return deny(session.getConnectionId(), userId);
    }

    private String deny(String connectionId, String userId) {
        if (isBlank(userId)) {
            return "缺少 userId，拒绝操作该 SSH 连接";
        }
        if (isBlank(connectionId)) {
            return "缺少 connectionId";
        }
        SshConnectionEntity connection = sshConnectionService.getConnection(connectionId);
        if (connection == null) {
            return "SSH 连接不存在";
        }
        if (!userId.equals(connection.getUserId())) {
            log.warn("[security] 终端归属校验失败 userId={}, connectionId={}, owner={}",
                    userId, connectionId, connection.getUserId());
            return "无权操作该 SSH 连接";
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}