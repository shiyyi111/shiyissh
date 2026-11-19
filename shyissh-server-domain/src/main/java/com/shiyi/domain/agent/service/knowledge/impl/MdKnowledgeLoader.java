package com.shiyi.domain.agent.service.knowledge.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * md 静态知识切分器：把一个目录下的 {@code *.md} 按二级标题切成一条条知识。
 * <p>
 * <b>为什么按 {@code ## KB-001} 这种编号切</b>：这些文档本来就是人写给检索用的，
 * 一节就是一个可独立命中的知识单元（现象/判定/处理/命令），切在编号上是作者的真实意图；
 * 按长度硬切会把"命令"和"风险"切到两条里，检索命中半条反而更糟。
 * <p>
 * 编号本身就是业务主键（{@code KB-001}/{@code P-001}/{@code INC-001}），
 * 所以 {@code doc_id} 直接用编号：md 里改标题、改正文都还是同一个点，upsert 覆盖；
 * 删掉一节，下次同步时这个 id 不在"本次切出来的集合"里，就会被清理掉——
 * 这正是 payload 带全之后还能保持"源文件是唯一真相"的关键。
 * <p>
 * 只做纯文本解析：不引 Markdown 解析库（这份格式极其固定，引入依赖换不来稳定性）。
 *
 * @author shiyi
 */
final class MdKnowledgeLoader {

    /** 二级标题 + 编号：{@code ## KB-001}/{@code ## P-001}/{@code ## INC-001}（前缀 1~5 个大写字母）。 */
    private static final Pattern SECTION = Pattern.compile("^##\\s+([A-Z]{1,5}-\\d+)\\s+(.+?)\\s*$");

    private static final Pattern BULLET = Pattern.compile("^-\\s+([^:：]+)[:：]\\s*(.*)$");

    private MdKnowledgeLoader() {
    }

    /**
     * 读目录下所有 md，切段返回；文件不存在返回空列表（不抛异常，启动阶段不该因为文档缺失而失败）。
     */
    static List<Section> loadAll(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Section> sections = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
        for (Path file : files) {
            sections.addAll(parseFile(file));
        }
        return sections;
    }

    private static List<Section> parseFile(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
        List<Section> sections = new ArrayList<>();
        String currentId = null;
        String currentTitle = null;
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            Matcher matcher = SECTION.matcher(line);
            if (matcher.matches()) {
                if (currentId != null) {
                    sections.add(build(file, currentId, currentTitle, body.toString()));
                }
                currentId = matcher.group(1);
                currentTitle = matcher.group(2).trim();
                body.setLength(0);
                continue;
            }
            if (currentId != null) {
                body.append(line).append('\n');
            }
        }
        if (currentId != null) {
            sections.add(build(file, currentId, currentTitle, body.toString()));
        }
        return sections;
    }

    private static Section build(Path file, String docId, String title, String rawBody) {
        String body = rawBody.strip();
        Map<String, String> bullets = parseBullets(body);
        String kind = kindOf(docId);
        return new Section(docId, kind, title, body,
                bullets.getOrDefault("命令", ""),
                bullets.getOrDefault("标签", ""),
                bullets.getOrDefault("级别", ""),
                file.getFileName().toString(),
                sha1(title + "\n" + body));
    }

    /**
     * 取单行字段；`- 命令:` 的值可能占多行（命令块），一直吃到下一个 `- xxx:` 之前。
     */
    private static Map<String, String> parseBullets(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        String currentKey = null;
        StringBuilder currentValue = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            Matcher matcher = BULLET.matcher(line.strip());
            if (matcher.matches()) {
                if (currentKey != null) {
                    values.put(currentKey, currentValue.toString().strip());
                }
                currentKey = matcher.group(1).strip();
                currentValue = new StringBuilder(matcher.group(2).strip());
                continue;
            }
            if (currentKey != null && !line.isBlank()) {
                if (!currentValue.isEmpty()) {
                    currentValue.append('\n');
                }
                currentValue.append(line.strip());
            }
        }
        if (currentKey != null) {
            values.put(currentKey, currentValue.toString().strip());
        }
        return values;
    }

    private static String kindOf(String docId) {
        if (docId.startsWith("KB-")) {
            return "runbook";
        }
        if (docId.startsWith("P-")) {
            return "policy";
        }
        if (docId.startsWith("INC-")) {
            return "incident";
        }
        return "doc";
    }

    private static String sha1(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    /**
     * 一条知识：{@code docId} 是业务主键，{@code content} 是完整正文（检索时直接喂给模型，不再回表）。
     */
    record Section(String docId, String kind, String title, String content,
                   String commands, String tags, String severity, String path, String contentHash) {
    }
}