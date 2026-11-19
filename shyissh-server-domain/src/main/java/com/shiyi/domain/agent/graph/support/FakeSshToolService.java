package com.shiyi.domain.agent.graph.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 假 SSH 工具（P1 用）。
 * <p>
 * P1 阶段不依赖真实服务器，所有命令都回固定的 df 输出，用来验证图的装配、
 * 工具参数传递、结果回填、预算控制。终端会话可用时走真实 SSH，本类只在没有终端会话时兜底。
 * <p>
 * 保留这个类的价值：图骨架可以离线跑测试，不因为服务器停了就阻塞开发。
 *
 * @author shiyi
 */
@Service
public class FakeSshToolService {

    private static final Logger log = LoggerFactory.getLogger(FakeSshToolService.class);

    private static final String DF_OUTPUT = "/dev/vda1        40G   18G   20G  48% /";

    /** 记录所有被执行的命令，测试里用来断言"模型确实传了真实参数"。 */
    private final List<String> executed = new CopyOnWriteArrayList<>();

    public List<String> executed() {
        return executed;
    }

    /**
     * 执行命令（假实现）。
     *
     * @param command 命令
     * @return 命令输出
     */
    public String execute(String command) {
        log.info("[FakeSsh] 收到命令: {}", command);
        if (command == null || command.isBlank()) {
            return "";
        }
        String trimmed = command.trim();
        executed.add(trimmed);
        if (trimmed.contains("df")) {
            return DF_OUTPUT;
        }
        if (trimmed.contains("uname")) {
            return "Linux iZbp1xxxx 5.10.0-28-amd64 #1 SMP x86_64 GNU/Linux";
        }
        if (trimmed.startsWith("pwd")) {
            return "/root";
        }
        return "(fake output for: " + trimmed + ")";
    }
}