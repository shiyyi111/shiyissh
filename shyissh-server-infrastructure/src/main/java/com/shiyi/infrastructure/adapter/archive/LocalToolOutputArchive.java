package com.shiyi.infrastructure.adapter.archive;

import com.shiyi.domain.agent.adapter.archive.IToolOutputArchive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 工具输出归档的本地文件实现。
 * <p>
 * 目录结构：{@code <根目录>/<sessionId>/<toolCallId>-<时间戳>.log}。
 * 按会话分目录是为了"删一个会话就能连带清掉它产生的所有大文件"；
 * 文件名带 toolCallId 与时间戳，是为了同一轮里多条命令、或多轮里同一个 callId
 * （模型有时会重复生成）都能各存一份、不互相覆盖。
 * <p>
 * 介质选择：本地磁盘足够（单实例部署、单条输出 10 万字符量级），
 * 换成对象存储只需要替换这个类，domain 侧接口不动。
 *
 * @author shiyi
 */
@Slf4j
@Component
public class LocalToolOutputArchive implements IToolOutputArchive {

    /** 文件名里的时间戳格式，精确到秒即可（同一秒内同 callId 概率极低，且会覆盖也只是留档）。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 归档根目录；默认落在系统临时目录下，生产建议配成独立磁盘目录便于容量监控。 */
    @Value("${app.agent.tool-output-dir:${java.io.tmpdir}/shiyissh-tool-output}")
    private String baseDir;

    @Override
    public String archive(String sessionId, String toolCallId, String toolName, String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        try {
            Path dir = Paths.get(baseDir, safeSegment(sessionId));
            Files.createDirectories(dir);
            String fileName = safeSegment(toolCallId) + "-" + LocalDateTime.now().format(TIME_FORMAT) + ".log";
            Path file = dir.resolve(fileName);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            log.info("[observe] 工具输出已归档 tool={}, chars={}, path={}", toolName, content.length(), file);
            return file.toAbsolutePath().toString();
        } catch (IOException | RuntimeException e) {
            // 归档只是"留档"这个附加能力，失败了不能影响主流程（模型照样拿截断版继续干活）
            log.warn("[observe] 工具输出归档失败，已忽略：{}", e.getMessage());
            return null;
        }
    }

    /** 把会话 ID / callId 变成安全的目录名或文件名片段：路径分隔符等非法字符一律换成下划线。 */
    private static String safeSegment(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}