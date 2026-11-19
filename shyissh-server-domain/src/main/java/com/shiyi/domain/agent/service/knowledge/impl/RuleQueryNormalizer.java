package com.shiyi.domain.agent.service.knowledge.impl;

import com.shiyi.domain.agent.adapter.repository.ISynonymRepository;
import com.shiyi.domain.agent.model.entity.SynonymEntity;
import com.shiyi.domain.agent.service.knowledge.QueryNormalizer;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于规则与同义词表的查询归一化。
 * <p>
 * 这一层刻意**不用模型**：它做的是确定性映射，同一句话任何时候都该得到同一个结果，
 * 否则召回结果不可复现、也没法写回归测试。模型只负责"语义改写"，那是另一层（可开关）。
 * <p>
 * 干三件事：
 * <ol>
 *   <li><b>折叠空白</b>：多空格、换行、首尾空白统一成一个空格；</li>
 *   <li><b>同义词替换</b>：{@code 磁盘满} → {@code 磁盘空间不足 No space left on device}，
 *       让口语说法能命中日志原文（按 alias 长度从长到短替换，避免短词先吃掉长词）；</li>
 *   <li><b>抽强信号</b>：主机名、IP、端口、错误码、命令名。一旦命中就<b>不需要改写了</b>——
 *       {@code prod-db-03}、{@code 502}、{@code df} 这类词本身就是最精确的检索条件，
 *       再让模型改写只会引入噪声。</li>
 * </ol>
 *
 * @author shiyi
 */
@Service
public class RuleQueryNormalizer implements QueryNormalizer {

    private static final Logger log = LoggerFactory.getLogger(RuleQueryNormalizer.class);

    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    /** 主机名：含连字符且以数字结尾（prod-db-03 / web-1 / k8s-node-02）。 */
    private static final Pattern HOST_LIKE = Pattern.compile("\\b[a-z][a-z0-9]*(?:-[a-z0-9]+)*-?\\d[a-z0-9]*\\b");

    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");

    /** 端口：:8080 / 端口 3306。 */
    private static final Pattern PORT = Pattern.compile("(?::|端口\\s*)(\\d{2,5})\\b");

    /** 常见错误码 / HTTP 状态码。 */
    private static final Pattern ERROR_CODE = Pattern.compile("\\b(?:[45]\\d{2}|errno\\s*\\d+)\\b");

    /** 命令名：出现即说明用户在说具体操作，字面检索最准。 */
    private static final Set<String> COMMAND_WORDS = Set.of(
            "df", "du", "top", "htop", "ps", "free", "vmstat", "iostat", "sar", "netstat", "ss",
            "lsof", "curl", "wget", "tail", "head", "grep", "awk", "sed", "find", "systemctl",
            "journalctl", "dmesg", "docker", "kubectl", "nginx", "mysql", "redis", "java", "kill");

    /** 切词时丢弃的词：中文虚词与英文停用词，避免它们稀释 FULLTEXT 的匹配。 */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "是", "在", "有", "和", "与", "怎么", "如何", "为什么", "什么", "这个", "那个",
            "the", "a", "an", "is", "are", "how", "why", "what", "to", "of", "in", "on", "and");

    @Resource
    private ISynonymRepository synonymRepository;

    /** 同义词表缓存时长（分钟）；表很小，整表缓存即可。 */
    @Value("${app.rag.normalize.synonym-cache-minutes:30}")
    private long synonymCacheMinutes = 30;

    /** 同义词开关；关掉后只做空白折叠与强信号抽取，不再改写用户原话。 */
    @Value("${app.rag.normalize.synonym-enabled:true}")
    private boolean synonymEnabled = true;

    /** 归一化后最多保留多少个检索词。 */
    @Value("${app.rag.normalize.max-terms:8}")
    private int maxTerms = 8;

    /** 同义词表快照；null 表示尚未加载。 */
    private volatile SynonymSnapshot snapshot;

    @Override
    public Normalized normalize(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return new Normalized("", "", List.of(), List.of());
        }
        String original = rawQuery.strip();
        String text = MULTI_SPACE.matcher(original).replaceAll(" ").strip();

        List<SynonymEntity> synonyms = synonymEnabled ? loadSynonyms() : List.of();
        for (SynonymEntity synonym : synonyms) {
            String alias = synonym.getAlias();
            String canonical = synonym.getCanonical();
            if (alias == null || alias.isBlank() || canonical == null || canonical.isBlank()) {
                continue;
            }
            if (text.contains(alias)) {
                text = text.replace(alias, canonical);
            }
        }

        List<String> strongSignals = extractStrongSignals(text);
        List<String> terms = extractTerms(text);
        return new Normalized(original, MULTI_SPACE.matcher(text).replaceAll(" ").strip(), terms, strongSignals);
    }

    /**
     * 加载同义词：按 alias 长度降序，保证"磁盘满了"先于"磁盘"被替换。
     */
    private List<SynonymEntity> loadSynonyms() {
        long now = System.currentTimeMillis();
        SynonymSnapshot current = snapshot;
        if (current != null && now - current.loadedAt() < synonymCacheMinutes * 60_000L) {
            return current.synonyms();
        }
        List<SynonymEntity> loaded;
        try {
            loaded = synonymRepository == null ? List.of() : synonymRepository.queryEnabled();
        } catch (Exception e) {
            // 同义词表读失败不能影响检索主流程：退化成"不做别名替换"
            log.warn("加载同义词表失败，本次归一化跳过别名替换：{}", e.getMessage());
            loaded = List.of();
        }
        List<SynonymEntity> ordered = new ArrayList<>(loaded);
        ordered.sort(Comparator.comparingInt((SynonymEntity s) -> s.getAlias() == null ? 0 : s.getAlias().length())
                .reversed());
        snapshot = new SynonymSnapshot(List.copyOf(ordered), now);
        return ordered;
    }

    /** 抽强信号：主机名 / IP / 端口 / 错误码 / 命令名。 */
    private List<String> extractStrongSignals(String text) {
        Set<String> signals = new LinkedHashSet<>();
        String lower = text.toLowerCase();

        Matcher ip = IPV4.matcher(lower);
        while (ip.find()) {
            signals.add(ip.group());
        }
        Matcher host = HOST_LIKE.matcher(lower);
        while (host.find()) {
            String value = host.group();
            // 纯数字、纯字母不算主机名；必须含连字符或字母数字混合才算"像主机名"
            if (value.length() >= 3 && !value.chars().allMatch(Character::isDigit)) {
                signals.add(value);
            }
        }
        Matcher port = PORT.matcher(lower);
        while (port.find()) {
            signals.add(port.group(1));
        }
        Matcher code = ERROR_CODE.matcher(lower);
        while (code.find()) {
            signals.add(code.group());
        }
        for (String word : lower.split("[^a-z0-9_]+")) {
            if (COMMAND_WORDS.contains(word)) {
                signals.add(word);
            }
        }
        return List.copyOf(signals);
    }

    /** 切词并去停用词，最多 {@link #maxTerms} 个。 */
    private List<String> extractTerms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        for (String token : text.toLowerCase().split("[\\s,，。；;：:、!！?？()（）\\[\\]\"'`]+")) {
            if (token.isBlank() || STOP_WORDS.contains(token)) {
                continue;
            }
            // 单个汉字的信息量太低，放过（"满"这种能命中，但会引入大量噪声）
            if (token.length() == 1 && !isAsciiLetterOrDigit(token.charAt(0))) {
                continue;
            }
            terms.add(token);
            if (terms.size() >= maxTerms) {
                break;
            }
        }
        return List.copyOf(terms);
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /** 同义词快照：数据 + 加载时间，用于判断是否过期。 */
    private record SynonymSnapshot(List<SynonymEntity> synonyms, long loadedAt) {
    }
}
