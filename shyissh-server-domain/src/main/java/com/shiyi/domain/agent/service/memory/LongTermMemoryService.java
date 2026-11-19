package com.shiyi.domain.agent.service.memory;

import com.shiyi.domain.agent.adapter.repository.IChatHistoryRepository;
import com.shiyi.domain.agent.adapter.repository.ILongTermMemoryRepository;
import com.shiyi.domain.agent.model.entity.ChatMessageEntity;
import com.shiyi.domain.agent.model.entity.LongTermMemoryEntity;
import com.shiyi.domain.agent.service.index.VectorIndexWorker;
import com.shiyi.domain.agent.service.ILongTermMemoryService;
import com.shiyi.domain.agent.service.context.TokenEstimator;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 长期记忆服务实现——本节核心组件
 * <p>
 * 自动从对话流中提取 4 种结构化记忆，并支持关键词召回（粗筛+精排两阶段策略）。
 * <p>
 * <b>四种记忆类型：</b>
 * <table border="1">
 * <tr><th>类型</th><th>触发条件</th><th>key 策略</th><th>置信度</th></tr>
 * <tr><td>USER_PREFERENCE</td><td>用户消息含偏好词（"以后""默认""记住"等）</td><td>sha1("pref:"+内容)</td><td>0.85</td></tr>
 * <tr><td>ENVIRONMENT_FACT</td><td>工具输出匹配 OS 关键词</td><td>"os:ubuntu"</td><td>0.70</td></tr>
 * <tr><td>SOFTWARE_FACT</td><td>工具输出匹配软件版本模式</td><td>"redis:version"</td><td>0.78~0.82</td></tr>
 * <tr><td>TROUBLESHOOTING_CASE</td><td>工具失败 / 助手含"结论""原因"等</td><td>sha1("failure:"/"assistant:"+内容)</td><td>0.68~0.72</td></tr>
 * </table>
 * <p>
 * <b>召回策略：粗筛+精排</b>
 * <ul>
 *   <li>粗筛：从 DB 拉最近 120 条记忆</li>
 *   <li>精排：关键词重叠打分（重叠数×2 + USER_PREFERENCE 额外+1），按分数降序取 Top-N</li>
 * </ul>
 * <p>
 * 设计原则：零外部依赖（不需要 Embedding API、不需要向量数据库），纯 MySQL + 关键词匹配。
 * 对于 SSH 运维场景，关键词（nginx、redis、502、timeout）天然具有高区分度，召回精度够用。
 */
@Slf4j
@Service
public class LongTermMemoryService implements ILongTermMemoryService {

    /** 记忆类型：用户偏好（最高价值，用户自己说的，置信度 0.85） */
    private static final String TYPE_USER_PREFERENCE = "USER_PREFERENCE";

    /** 记忆类型：环境事实（从工具输出检测 OS，置信度 0.70） */
    private static final String TYPE_ENVIRONMENT_FACT = "ENVIRONMENT_FACT";

    /** 记忆类型：软件版本（从工具输出检测版本号，置信度 0.78~0.82） */
    private static final String TYPE_SOFTWARE_FACT = "SOFTWARE_FACT";

    /** 记忆类型：排查案例（失败信号/助手结论，置信度 0.68~0.72） */
    private static final String TYPE_TROUBLESHOOTING_CASE = "TROUBLESHOOTING_CASE";

    /** 作用域：跨主机通用（用户偏好） */
    private static final String SCOPE_USER = "USER";

    /** 作用域：绑定某台主机（环境事实、软件版本、该机器上的排查案例） */
    private static final String SCOPE_HOST = "HOST";

    /**
     * 召回分数阈值（融合分兜底）：低于该值的记忆不值得占用上下文。
     * <p>
     * 它只是三道门里的一道：关键词覆盖率够高、或者无关键词查询时重要度够高，都可以单独放行。
     * 只看融合总分会出现"关键词命中得很准、但这条记忆创建得早/重要度一般"被 0.6 挡掉的情况，
     * 而那种记忆恰恰是上下文最该带上的。
     */
    private static final double RECALL_THRESHOLD = 0.6d;

    /** 关键词覆盖率门：IDF 加权覆盖达到该比例，说明这条记忆确实在讲这个问题，单凭它就放行 */
    private static final double KEYWORD_COVERAGE_MIN = 0.30d;

    /** 无关键词查询（如"继续""接着上次"）的重要度门：没有相关度可比，就按重要性兜底 */
    private static final double IMPORTANCE_GATE = 0.50d;

    /** 用户偏好检测关键词列表：消息中包含任一词即判定为可能的偏好表达 */
    private static final List<String> PREFERENCE_HINTS = List.of("以后", "默认", "记住", "优先", "不要", "请用", "习惯", "统一使用");

    /** 通用软件版本正则：匹配 "nginx version: 1.24.0"、"mysql Ver 8.0.42" 等格式 */
    private static final Pattern VERSION_PATTERN = Pattern.compile("(?i)(nginx|redis|mysql|postgres|docker|java)[^\\n]{0,40}(version|ver|版本)[:= ]+([\\w.\\-]+)");

    /** Redis 专用版本正则：匹配 "redis_version:7.0.11" 格式（通用正则可能匹配不到） */
    private static final Pattern REDIS_VERSION_PATTERN = Pattern.compile("(?i)redis_version:([\\w.\\-]+)");

    /** 操作系统检测正则：匹配 ubuntu、centos、debian 等 OS 关键词 */
    private static final Pattern OS_PATTERN = Pattern.compile("(?i)(ubuntu|centos|debian|rocky|almalinux|fedora|linux|macos|darwin|windows)");

    @Resource
    private ILongTermMemoryRepository longTermMemoryRepository;

    @Resource
    private VectorIndexWorker vectorIndexWorker;

    /**
     * 语义召回（可选增强）。用字段注入而不是构造器注入：它内部依赖 embedding/向量库，
     * 那两者在 {@code app.rag.enabled=false} 时可能不存在，构造期强依赖会让应用起不来。
     */
    @Resource
    private MemoryVectorService memoryVectorService;

    /** 会话历史仓储（消息落库 + 冷启动恢复） */
    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    /**
     * {@inheritDoc}
     * <p>
     * 偏好检测逻辑：消息中包含 PREFERENCE_HINTS 任一关键词即判定为偏好表达。
     * memory_key 用 sha1("pref:"+内容) 确保同一偏好不重复入库——用户三次说"以后加 sudo"，
     * 三次 sha1 结果一样，走 saveOrUpdate 更新 hit_count。
     * 意图标签拼入关键词，提高后续召回命中率。
     */
    @Override
    public void recordUserMessage(String userId, String sessionId, String message, String intentLabel) {
        if (isBlank(userId) || isBlank(message)) {
            return;
        }

        String normalized = normalize(message);
        boolean looksLikePreference = PREFERENCE_HINTS.stream().anyMatch(normalized::contains);
        if (!looksLikePreference) {
            return;
        }

        saveMemory(LongTermMemoryEntity.builder()
                .userId(userId)
                .scope(SCOPE_USER)
                .hostKey("")
                .sessionId(sessionId)
                .memoryType(TYPE_USER_PREFERENCE)
                .memoryKey(sha1("pref:" + normalized))
                .content(truncate(message, 300))
                .keywords(joinKeywords(extractKeywords(message + " " + nullSafe(intentLabel))))
                .sourceRole("user")
                .confidence(0.85)
                .importance(0.9)
                .hitCount(1)
                .build());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 从工具执行结果中用正则匹配提取四类信息：
     * <ol>
     *   <li>操作系统检测（OS_PATTERN → ENVIRONMENT_FACT）</li>
     *   <li>通用软件版本检测（VERSION_PATTERN → SOFTWARE_FACT）</li>
     *   <li>Redis 专用版本检测（REDIS_VERSION_PATTERN → SOFTWARE_FACT）</li>
     *   <li>失败信号记录（仅 success=false 且 looksLikeHighSignalFailure → TROUBLESHOOTING_CASE）</li>
     * </ol>
     * 为什么要给 Redis 单独写一条正则？因为 Redis 版本输出格式 redis_version:7.0.11
     * 和 nginx version: nginx/1.x 格式不一样，通用正则可能匹配不到。
     */
    @Override
    public void recordToolObservation(String userId, String sessionId, String toolName, String resultContent, boolean success) {
        recordToolObservation(userId, sessionId, toolName, resultContent, success, "");
    }

    /**
     * 带主机维度的工具观察提取。
     * <p>
     * 从工具结果中提取的环境事实/软件版本天然绑定具体机器——"这台机器的 Redis 是 6.2"
     * 换一台机器就不成立，所以落库时 scope=HOST、host_key 记录主机标识；
     * 没有主机标识（离线假执行、终端未绑定）时退化为 USER 作用域。
     */
    @Override
    public void recordToolObservation(String userId, String sessionId, String toolName, String resultContent,
                                      boolean success, String hostKey) {
        if (isBlank(userId) || isBlank(resultContent)) {
            return;
        }

        String scope = isBlank(hostKey) ? SCOPE_USER : SCOPE_HOST;
        String host = isBlank(hostKey) ? "" : hostKey.trim();
        String text = truncate(resultContent, 500);

        // 1. 操作系统检测
        Matcher osMatcher = OS_PATTERN.matcher(text);
        if (osMatcher.find()) {
            String os = osMatcher.group(1).toLowerCase(Locale.ROOT);
            saveMemory(LongTermMemoryEntity.builder()
                    .userId(userId)
                    .scope(scope)
                    .hostKey(host)
                    .sessionId(sessionId)
                    .memoryType(TYPE_ENVIRONMENT_FACT)
                    .memoryKey("os:" + os)
                    .content("目标环境可能为 " + os)
                    .keywords(joinKeywords(extractKeywords(os + " environment system os")))
                    .sourceRole("tool")
                    .confidence(0.70)
                    .importance(0.60)
                    .hitCount(1)
                    .build());
        }

        // 2. 软件版本检测（通用正则）
        Matcher versionMatcher = VERSION_PATTERN.matcher(text);
        if (versionMatcher.find()) {
            String software = versionMatcher.group(1).toLowerCase(Locale.ROOT);
            String version = versionMatcher.group(3);
            saveMemory(LongTermMemoryEntity.builder()
                    .userId(userId)
                    .scope(scope)
                    .hostKey(host)
                    .sessionId(sessionId)
                    .memoryType(TYPE_SOFTWARE_FACT)
                    .memoryKey(software + ":version")
                    .content(software + " 版本为 " + version)
                    .keywords(joinKeywords(extractKeywords(software + " version " + version)))
                    .sourceRole("tool")
                    .confidence(0.78)
                    .importance(0.70)
                    .hitCount(1)
                    .build());
        }

        // 3. Redis 版本检测（专用正则，补充通用正则的遗漏）
        Matcher redisVersionMatcher = REDIS_VERSION_PATTERN.matcher(text);
        if (redisVersionMatcher.find()) {
            String version = redisVersionMatcher.group(1);
            saveMemory(LongTermMemoryEntity.builder()
                    .userId(userId)
                    .scope(scope)
                    .hostKey(host)
                    .sessionId(sessionId)
                    .memoryType(TYPE_SOFTWARE_FACT)
                    .memoryKey("redis:version")
                    .content("redis 版本为 " + version)
                    .keywords(joinKeywords(extractKeywords("redis version " + version)))
                    .sourceRole("tool")
                    .confidence(0.82)
                    .importance(0.75)
                    .hitCount(1)
                    .build());
        }

        // 4. 失败信号记录（仅工具执行失败时）
        if (!success && looksLikeHighSignalFailure(text)) {
            saveMemory(LongTermMemoryEntity.builder()
                    .userId(userId)
                    .scope(scope)
                    .hostKey(host)
                    .sessionId(sessionId)
                    .memoryType(TYPE_TROUBLESHOOTING_CASE)
                    .memoryKey(sha1("failure:" + normalize(text)))
                    .content("历史失败信号: " + text)
                    .keywords(joinKeywords(extractKeywords(text + " " + nullSafe(toolName))))
                    .sourceRole("tool")
                    .confidence(0.68)
                    .importance(0.80)
                    .hitCount(1)
                    .build());
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 只有当助手回复包含"结论""原因""建议""修复""根因"等关键词时才提取，
     * 避免把每一条中间推理过程都存下来——大部分回复没有记忆价值。
     */
    @Override
    public void recordAssistantConclusion(String userId, String sessionId, String assistantContent) {
        recordAssistantConclusion(userId, sessionId, assistantContent, "");
    }

    /**
     * 带主机维度的结论提取。
     * <p>
     * 排查结论（"根因是磁盘满，清理 /var/log 后恢复"）往往只对某一台机器成立，
     * 所以有主机标识时按 HOST 作用域落库；没有时退化为 USER 级通用经验。
     */
    @Override
    public void recordAssistantConclusion(String userId, String sessionId, String assistantContent, String hostKey) {
        if (isBlank(userId) || isBlank(assistantContent)) {
            return;
        }

        String normalized = normalize(assistantContent);
        if (!(normalized.contains("结论") || normalized.contains("原因") || normalized.contains("建议") || normalized.contains("修复") || normalized.contains("根因"))) {
            return;
        }

        String scope = isBlank(hostKey) ? SCOPE_USER : SCOPE_HOST;
        String host = isBlank(hostKey) ? "" : hostKey.trim();
        String summary = truncate(assistantContent, 400);
        saveMemory(LongTermMemoryEntity.builder()
                .userId(userId)
                .scope(scope)
                .hostKey(host)
                .sessionId(sessionId)
                .memoryType(TYPE_TROUBLESHOOTING_CASE)
                .memoryKey(sha1("assistant:" + normalize(summary)))
                .content(summary)
                .keywords(joinKeywords(extractKeywords(summary)))
                .sourceRole("assistant")
                .confidence(0.72)
                .importance(0.85)
                .hitCount(1)
                .build());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 用户消息落库（仅首轮）+ 偏好记忆提取，case 层统一入口。
     */
    @Override
    public void saveUserMessage(String userId, String sessionId, String message, String intentLabel, boolean firstRound) {
        if (firstRound && !isBlank(sessionId) && !isBlank(message)) {
            try {
                chatHistoryRepository.saveMessage(ChatMessageEntity.builder()
                        .sessionId(sessionId)
                        .role("user")
                        .content(message)
                        .tokenCount(TokenEstimator.estimateMessage(message))
                        .build());
            } catch (Exception e) {
                log.warn("保存用户消息落库失败 sessionId={}", sessionId, e);
            }
        }
        recordUserMessage(userId, sessionId, message, intentLabel);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 助手回复落库 + 结论记忆提取，图链路的统一入口。
     */
    @Override
    public void saveAssistantMessage(String userId, String sessionId, String assistantContent) {
        saveAssistantMessage(userId, sessionId, assistantContent, null);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 有模型 usage 时用它落库：{@code chat_message.token_count} 是历史回读时算 token 预算的依据，
     * 用 {@code length()/2} 粗估会让预算偏松（中文 1 字≈1 token，粗估只有一半）。
     */
    @Override
    public void saveAssistantMessage(String userId, String sessionId, String assistantContent, Integer tokenCount) {
        saveAssistantMessage(userId, sessionId, assistantContent, tokenCount, "");
    }

    /**
     * {@inheritDoc}
     * <p>
     * 最终结论是按某台机器的现象推出来的（"根因是磁盘满"对另一台机器不成立），
     * 因此 hostKey 非空时结论记忆归入 HOST 作用域；没有主机（纯问答）则退化为 USER。
     */
    @Override
    public void saveAssistantMessage(String userId, String sessionId, String assistantContent,
                                     Integer tokenCount, String hostKey) {
        if (isBlank(assistantContent)) {
            return;
        }
        int tokens = (tokenCount != null && tokenCount > 0) ? tokenCount : TokenEstimator.estimateMessage(assistantContent);
        try {
            chatHistoryRepository.saveMessage(ChatMessageEntity.builder()
                    .sessionId(sessionId)
                    .role("assistant")
                    .content(assistantContent)
                    .tokenCount(tokens)
                    .build());
        } catch (Exception e) {
            log.warn("保存助手消息落库失败 sessionId={}", sessionId, e);
        }
        recordAssistantConclusion(userId, sessionId, assistantContent, hostKey);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 工具结果落库（priority=HIGH）+ 环境事实/软件版本/失败信号记忆提取，case 层统一入口。
     */
    @Override
    public void saveToolMessage(String userId, String sessionId, String toolName, String toolCallId, String resultContent, boolean success) {
        saveToolMessage(userId, sessionId, toolName, toolCallId, resultContent, success, "");
    }

    /**
     * {@inheritDoc}
     * <p>
     * 落库（chat_message）与记忆抽取是两件事：前者记录会话流水、后者沉淀跨会话知识。
     */
    @Override
    public void saveToolMessage(String userId, String sessionId, String toolName, String toolCallId,
                                String resultContent, boolean success, String hostKey) {
        if (isBlank(resultContent)) {
            return;
        }
        try {
            chatHistoryRepository.saveMessage(ChatMessageEntity.builder()
                    .sessionId(sessionId)
                    .role("tool")
                    .content(resultContent)
                    .toolName(toolName)
                    .toolCallId(toolCallId)
                    .tokenCount(TokenEstimator.estimateMessage(resultContent))
                    .build());
        } catch (Exception e) {
            log.warn("保存工具消息落库失败 sessionId={}, tool={}", sessionId, toolName, e);
        }
        recordToolObservation(userId, sessionId, toolName, resultContent, success, hostKey);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 冷启动恢复：委托仓储层取最近 N 条消息（时间正序）。
     */
    @Override
    public List<ChatMessageEntity> getRecentMessages(String sessionId, int limit) {
        if (isBlank(sessionId)) {
            return List.of();
        }
        try {
            return chatHistoryRepository.getRecentMessages(sessionId, limit);
        } catch (Exception e) {
            log.warn("查询最近消息失败 sessionId={}", sessionId, e);
            return List.of();
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 两阶段召回（从DB直接拿，会比 RAG 更准）：
     * <ol>
     *   <li>粗筛：从 DB 拉最近 120 条记忆</li>
     *   <li>精排：关键词重叠打分，过滤 score>0，按分数降序+更新时间降序取 Top-N</li>
     * </ol>
     */
    @Override
    public List<LongTermMemoryEntity> queryRelevantMemories(String userId, String query, int limit) {
        return queryRelevantMemories(userId, "", query, limit);
    }

    /**
     * 带主机维度的两阶段召回。
     * <p>
     * 阶段一（候选池）由仓储层合并三个来源：该主机的全部 HOST 事实、跨主机的用户偏好、
     * 最近 60 条记忆。相比"只取最近 120 条"，这样解决了两个问题：
     * <ul>
     *   <li>半年前记录的"这台机器 Redis 端口 6380"不会被新噪音挤出候选池</li>
     *   <li>用户偏好不需要每轮重新命中关键词，天然在池子里</li>
     * </ul>
     * <p>
     * 阶段二（多因子打分）不再只数关键词，而是把"相关度 + 类型权重 + 置信度 +
     * 时间衰减 + 使用频次"加权求和，最后按阈值 0.6 过滤。命中后回写
     * hit_count/last_used_at，让"常用记忆"在后续召回中自然靠前。
     */
    @Override
    public List<LongTermMemoryEntity> queryRelevantMemories(String userId, String hostKey, String query, int limit) {
        if (isBlank(userId)) {
            return List.of();
        }

        // 语义候选优先：向量库把"同义不同词"（"网关报错" vs "nginx 502"）的记忆也捞进来；
        // 向量库不可用/集合为空时退回原来的关键词候选池，记忆召回不允许因为增强项而整体失效。
        Map<Long, Double> semanticScores = memoryVectorService.recallScores(userId, hostKey, query,
                MemoryVectorService.DEFAULT_RECALL_LIMIT);
        List<LongTermMemoryEntity> candidates = semanticScores.isEmpty()
                ? longTermMemoryRepository.queryRecallCandidates(userId, hostKey)
                : longTermMemoryRepository.findByIds(new java.util.ArrayList<>(semanticScores.keySet()));
        if (candidates.isEmpty()) {
            return List.of();
        }

        Set<String> queryKeywords = extractKeywords(query);
        // 每调记忆的关键词只切一次：下面算 IDF 覆盖率要用，重复 extractKeywords 会把
        // "候选条数 × 关键词数" 次正则切分开销放大到几十毫秒（候选池最多 300 条）。
        Map<Long, Set<String>> memoryKeywords = new HashMap<>();
        for (LongTermMemoryEntity candidate : candidates) {
            memoryKeywords.put(candidate.getId(),
                    extractKeywords(nullSafe(candidate.getKeywords()) + " " + nullSafe(candidate.getContent())));
        }
        Map<String, Double> idf = buildIdf(candidates, memoryKeywords, queryKeywords);
        double totalIdf = idf.values().stream().mapToDouble(Double::doubleValue).sum();

        boolean hasKeywords = !queryKeywords.isEmpty();
        // 三道门放行任意一道即可：关键词覆盖率够高 / 融合总分够高 / 无关键词时重要度够高。
        // 只卡融合总分会把"命中很准但创建得早"的记忆挡在门外，那种记忆恰恰最该进上下文。
        List<MemoryScore> scored = candidates.stream()
                .map(memory -> score(memory, queryKeywords, memoryKeywords.get(memory.getId()), idf, totalIdf,
                        semanticScores.get(memory.getId())))
                .filter(ms -> passGate(ms, hasKeywords))
                .sorted(Comparator.comparingDouble(MemoryScore::score).reversed()
                        .thenComparing(ms -> ms.memory().getUpdatedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit > 0 ? limit : 5)
                .collect(Collectors.toList());
        List<LongTermMemoryEntity> recalled = scored.stream()
                .map(MemoryScore::memory)
                .collect(Collectors.toList());

        // 写入侧审计：召回规模必须能解释清楚——候选多少、放行多少、最好的那条多少分。
        // 没有这条日志，"记忆效果不好"只能靠猜是候选池没捞到、还是分数算低了。
        log.info("[memory] op=recall userId={} hostKey={} candidates={} semantic={} hasKeywords={} recalled={} topScore={}",
                userId, hostKey, candidates.size(), semanticScores.size(), hasKeywords, recalled.size(),
                scored.isEmpty() ? 0d : Math.round(scored.get(0).score() * 1000) / 1000d);

        // 使用反馈：被召回的记记忆 hit_count+1、last_used_at=now（失败不影响主流程）
        try {
            longTermMemoryRepository.touchUsage(recalled.stream().map(LongTermMemoryEntity::getId).collect(Collectors.toList()));
        } catch (Exception e) {
            log.warn("回写记忆使用反馈失败 userId={}", userId, e);
        }
        return recalled;
    }

    /**
     * {@inheritDoc}
     * <p>
     * memoryKey 用 sha1("tool:" + 类型 + 内容) 生成：同一段经验被模型保存两次会命中
     * 唯一键走更新，而不同机器的同类经验因为 hostKey 不同各自成行。
     */
    @Override
    public void saveUserMemory(String userId, String sessionId, String content, String kind, String hostKey) {
        if (isBlank(userId) || isBlank(content)) {
            return;
        }

        String scope = isBlank(hostKey) ? SCOPE_USER : SCOPE_HOST;
        String host = isBlank(hostKey) ? "" : hostKey.trim();
        String memoryType = resolveMemoryType(kind);
        String normalized = normalize(content);

        saveMemory(LongTermMemoryEntity.builder()
                .userId(userId)
                .scope(scope)
                .hostKey(host)
                .sessionId(sessionId)
                .memoryType(memoryType)
                .memoryKey(sha1("tool:" + memoryType + ":" + normalized))
                .content(truncate(content, 500))
                .keywords(joinKeywords(extractKeywords(content)))
                .sourceRole("assistant")
                .confidence(0.90)
                .importance(0.80)
                .hitCount(1)
                .build());
    }

    /**
     * 把模型给的 kind 归一到四种记忆类型。
     * <p>
     * 不直接信任模型输出：kind 是模型自己填的字符串，拼错、大小写、中英文都可能出现，
     * 一律落到已知类型上，避免脏数据进入召回的打分逻辑（类型权重按字符串匹配）。
     */
    private String resolveMemoryType(String kind) {
        if (isBlank(kind)) {
            return TYPE_ENVIRONMENT_FACT;
        }

        String key = kind.trim().toUpperCase();
        if (key.contains("PREFERENCE")) {
            return TYPE_USER_PREFERENCE;
        }
        if (key.contains("SOFTWARE") || key.contains("VERSION")) {
            return TYPE_SOFTWARE_FACT;
        }
        if (key.contains("CASE") || key.contains("TROUBLE") || key.contains("FAIL")) {
            return TYPE_TROUBLESHOOTING_CASE;
        }
        return TYPE_ENVIRONMENT_FACT;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 输出格式：每行一条记忆，"- [类型] 内容"
     */
    @Override
    public String buildMemorySummary(String userId, String query, int limit) {
        return buildMemorySummary(userId, "", query, limit);
    }

    /**
     * 生成记忆摘要（带主机标识）。
     * <p>
     * 传入主机后，HOST 作用域的记忆只在该主机的会话里参与召回；
     * 换一台机器，另一台机器的环境事实不会串进来。
     */
    @Override
    public String buildMemorySummary(String userId, String hostKey, String query, int limit) {
        List<LongTermMemoryEntity> memories = queryRelevantMemories(userId, hostKey, query, limit);
        if (memories.isEmpty()) {
            return "";
        }

        return memories.stream()
                .map(memory -> "- [" + memory.getMemoryType() + "] " + truncate(memory.getContent(), 160))
                .collect(Collectors.joining("\n"));
    }

    // ==================== 私有工具方法 ====================

    /**
     * 保存长期记忆（try-catch 旁路写入，失败不影响主流程）
     */
    private void saveMemory(LongTermMemoryEntity memoryEntity) {
        try {
            Long memoryId = longTermMemoryRepository.saveOrUpdate(memoryEntity);
            // 写入侧审计：记忆是"隐式写入"的，用户看不见它在攒什么。
            // 这条日志是唯一能回答"为什么模型记住了这个、没记住那个"的依据。
            log.info("[memory] op=write userId={} scope={} hostKey={} type={} key={} chars={} confidence={} importance={}",
                    memoryEntity.getUserId(), memoryEntity.getScope(), memoryEntity.getHostKey(),
                    memoryEntity.getMemoryType(), memoryEntity.getMemoryKey(),
                    memoryEntity.getContent() == null ? 0 : memoryEntity.getContent().length(),
                    memoryEntity.getConfidence(), memoryEntity.getImportance());
            if (memoryId != null && vectorIndexWorker != null) {
                // 交给单消费者 worker 做向量化：写请求里不做 embedding 外呼，也不依赖定时轮询
                vectorIndexWorker.enqueueMemory(memoryId);
            }
        } catch (Exception e) {
            log.warn("保存长期记忆失败 type={}, key={}", memoryEntity.getMemoryType(), memoryEntity.getMemoryKey(), e);
        }
    }

    /**
     * 多因子打分：把"相关度、类型、重要性、置信度、新鲜度、使用频次"合成一个 0~1 的分数。
     * <p>
     * 用**加权平均**而不是加权求和：求和的量纲随因子个数变化（关键词项还是"命中个数的 2 倍"），
     * 阈值只能靠试；平均后每一项都在 0~1，总分天然落在 0~1，阈值才有可比性。
     * <pre>
     *   有关键词：0.40×覆盖率 + 0.10×类型 + 0.20×重要性 + 0.15×置信度 + 0.10×新鲜度 + 0.05×使用频次
     *   无关键词：0.35×重要性 + 0.25×类型 + 0.20×置信度 + 0.12×新鲜度 + 0.08×使用频次
     * </pre>
     * 覆盖率用 IDF 加权（见 {@link #buildIdf}）：命中"nginx"比命中"问题"更值钱。
     * <p>
     * 为什么不用 BM25/向量？记忆总量通常几十到几百条，候选池已经按用户/主机收窄，
     * 这个规模下"关键词 + 多因子"召回质量足够，且可解释、零额外依赖、零 API 成本。
     *
     * @param memory        长期记忆
     * @param queryKeywords 查询关键词集合
     * @param memoryKeywords 该条记忆的关键词集合
     * @param idf           各查询关键词的 IDF 权重
     * @param totalIdf      查询关键词的 IDF 权重之和（覆盖率的分母）
     * @return 打分结果（含覆盖率与重要度，供 {@link #passGate} 判门）
     */
    private MemoryScore score(LongTermMemoryEntity memory, Set<String> queryKeywords,
                              Set<String> memoryKeywords, Map<String, Double> idf, double totalIdf,
                              Double semanticCoverage) {
        double typeWeight = typeWeight(memory.getMemoryType());
        double importance = clamp01(memory.getImportance() == null ? 0.5d : memory.getImportance().doubleValue());
        double confidence = clamp01(memory.getConfidence() == null ? 0.5d : memory.getConfidence().doubleValue());
        double recency = recencyFactor(memory);
        // 使用频次饱和到 1：hit=10 次即满分，避免"被点开过 200 次"的老条目靠频次碾压相关度
        double usage = Math.min(1d, Math.log(1 + Math.max(0, memory.getHitCount() == null ? 0 : memory.getHitCount()))
                / Math.log(11d));

        if (queryKeywords.isEmpty()) {
            // 无查询关键词（如"继续"）：没有相关度可言，只能靠重要度、类型、置信度、时效排序
            double total = 0.35 * importance + 0.25 * typeWeight + 0.20 * confidence
                    + 0.12 * recency + 0.08 * usage;
            return new MemoryScore(memory, total, 0d, importance);
        }

        Set<String> keywords = memoryKeywords == null ? Set.of() : memoryKeywords;
        double matchedIdf = 0d;
        for (String keyword : queryKeywords) {
            if (keywords.contains(keyword)) {
                matchedIdf += idf.getOrDefault(keyword, 1d);
            }
        }
        // 相关度 = IDF 加权覆盖率（0~1）：命中"nginx"比命中"问题"更值钱，
        // 而覆盖率而不是命中个数，是为了不让"命中 5 个通用词"打败"命中 1 个专有词"。
        double coverage = totalIdf <= 0d ? 0d : Math.min(1d, matchedIdf / totalIdf);
        // 语义覆盖率与字面覆盖率取较大值：两者衡量的是同一件事（这条记忆跟问题有多相关），
        // 一个是字面命中、一个是向量近邻，任一成立就算相关；相加会让"两边都半命中"虚高。
        if (semanticCoverage != null && semanticCoverage > coverage) {
            coverage = Math.min(1d, semanticCoverage);
        }
        double total = 0.40 * coverage + 0.10 * typeWeight + 0.20 * importance + 0.15 * confidence
                + 0.10 * recency + 0.05 * usage;
        return new MemoryScore(memory, total, coverage, importance);
    }

    /**
     * 三道门放行任意一道即可。
     * <p>
     * 为什么不是"融合总分 ≥ 0.6"一刀切：融合分是个加权平均，任何一项短板都会把总分拉下来——
     * 一条创建了半年、重要度 0.5 但关键词命中得极准的记忆，总分可能只有 0.55；
     * 而一条重要度很高、跟当前问题只有一点点关系的记忆，总分也可能过 0.6。
     * 所以门要卡在"单信号"上：关键词覆盖率是主信号，重要度是无关键词时的替代信号，
     * 融合总分只作为两者都不突出时的兜底。
     *
     * @param scored      打分结果
     * @param hasKeywords 本次查询是否提取到了关键词
     * @return 是否召入上下文
     */
    private boolean passGate(MemoryScore scored, boolean hasKeywords) {
        if (scored.score() >= RECALL_THRESHOLD) {
            return true;
        }
        if (!hasKeywords) {
            // 无关键词查询没有相关度可比，只能看"这条记忆本身值不值得带"：重要度或绝对置信度
            return scored.importance() >= IMPORTANCE_GATE;
        }
        return scored.coverage() >= KEYWORD_COVERAGE_MIN;
    }

    /**
     * 计算查询关键词的 IDF：{@code log((N+1)/(df+1)) + 1}。
     * <p>
     * 加 1 是为了保证恒正（df=N 时也还有 1 分），否则"每篇都出现的词"权重会掉到 0，
     * 让覆盖率算式的分母也趋近 0、除出 NaN。
     *
     * @param candidates     本次候选记忆
     * @param memoryKeywords 每条候选的关键词集合（已预处理）
     * @param queryKeywords  查询关键词
     * @return keyword → idf
     */
    private Map<String, Double> buildIdf(List<LongTermMemoryEntity> candidates,
                                        Map<Long, Set<String>> memoryKeywords, Set<String> queryKeywords) {
        Map<String, Double> idf = new HashMap<>();
        if (queryKeywords.isEmpty() || candidates.isEmpty()) {
            return idf;
        }
        double total = candidates.size();
        for (String keyword : queryKeywords) {
            long df = candidates.stream()
                    .map(memory -> memoryKeywords.get(memory.getId()))
                    .filter(keywords -> keywords != null && keywords.contains(keyword))
                    .count();
            idf.put(keyword, Math.log((total + 1d) / (df + 1d)) + 1d);
        }
        return idf;
    }

    /** 把任意数夹到 0~1，防止历史数据里越界的 importance/confidence 把总分顶穿。 */
    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0d;
        }
        return Math.max(0d, Math.min(1d, value));
    }

    /**
     * 记忆类型权重（已归一化到 0~1，与其它因子同量级）：
     * 用户偏好全局适用（1.0），环境/软件事实次之（0.85），排查案例只在相似场景下有用（0.75）。
     */
    private double typeWeight(String memoryType) {
        if (TYPE_USER_PREFERENCE.equals(memoryType)) {
            return 1.0d;
        }
        if (TYPE_TROUBLESHOOTING_CASE.equals(memoryType)) {
            return 0.75d;
        }
        return 0.85d;
    }

    /**
     * 时间衰减因子：按记忆类型分层半衰期，基准取"最近被用到"与"最近更新"里更晚的那个。
     * <p>
     * 为什么不再统一用 90 天：不同类型的记忆寿命本来就不一样。
     * 用户偏好（"喜欢先看结论"）能用一年以上；环境与软件事实（"Redis 是 6.2"）半年内基本不会变；
     * 排查案例（"上次是内核参数把连接数卡住了"）依赖当时的版本与配置，过几个月可能已经不适用。
     * 统一半衰期会让"半年前的错误端口"和"半年前的用户偏好"以同样速度贬值，两头都不对。
     * <p>
     * 基准时间取 {@code lastUsedAt} 与 {@code updatedAt} 中较晚者：一条老记忆如果昨天刚被召回，
     * 它的时效性应该按昨天算，而不是按创建时间算。
     *
     * @param memory 记忆
     * @return 0~1 的衰减因子；时间未知时给 0.5（中性：不奖励也不惩罚）
     */
    private double recencyFactor(LongTermMemoryEntity memory) {
        Date updatedAt = memory.getUpdatedAt();
        Date lastUsedAt = memory.getLastUsedAt();
        Date base = updatedAt;
        if (lastUsedAt != null && (base == null || lastUsedAt.after(base))) {
            base = lastUsedAt;
        }
        if (base == null) {
            return 0.5d;
        }
        double days = Math.max(0d, (System.currentTimeMillis() - base.getTime()) / 86_400_000d);
        return Math.exp(-days / halfLifeDays(memory.getMemoryType()));
    }

    /** 各类型记忆的半衰期（天）：偏好最长，排查案例最短。 */
    private double halfLifeDays(String memoryType) {
        if (TYPE_USER_PREFERENCE.equals(memoryType)) {
            return 365d;
        }
        if (TYPE_TROUBLESHOOTING_CASE.equals(memoryType)) {
            return 45d;
        }
        if (TYPE_ENVIRONMENT_FACT.equals(memoryType) || TYPE_SOFTWARE_FACT.equals(memoryType)) {
            return 120d;
        }
        return 90d;
    }

    /**
     * 关键词提取：分割 → 过滤 → 去重。
     * <p>
     * <ol>
     *   <li>分割：用 [^a-z0-9_\u4e00-\u9fa5]+ 正则分割，保留英文/数字/下划线/中文</li>
     *   <li>过滤：长度 < 2 的片段丢弃，停用词丢弃</li>
     *   <li>去重：LinkedHashSet 保证唯一且保持插入顺序</li>
     * </ol>
     *
     * @param text 原始文本
     * @return 关键词集合
     */
    private Set<String> extractKeywords(String text) {
        if (isBlank(text)) {
            return Set.of();
        }
        String normalized = normalize(text);
        String[] parts = normalized.split("[^a-z0-9_\\u4e00-\\u9fa5]+");
        Set<String> keywords = new LinkedHashSet<>();
        Arrays.stream(parts)
                .map(String::trim)
                .filter(part -> part.length() >= 2)
                .filter(part -> !STOP_WORDS.contains(part))
                .forEach(keywords::add);
        return keywords;
    }

    /**
     * 将关键词集合拼接为逗号分隔的字符串，存入 DB 的 keywords 字段
     */
    private String joinKeywords(Set<String> keywords) {
        return String.join(",", keywords);
    }

    /**
     * 文本归一化：转小写 + 换行替换为空格 + trim
     */
    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace('\n', ' ').trim();
    }

    /**
     * 截断文本，超长部分用 "..." 表示
     */
    private String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }

    private String nullSafe(String text) {
        return text == null ? "" : text;
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /**
     * 判断工具输出是否包含高信号失败特征（permission denied、connection refused 等）。
     * 只有高信号失败才值得记录为排查案例，避免把无关紧要的失败都存下来。
     */
    private boolean looksLikeHighSignalFailure(String text) {
        String normalized = normalize(text);
        return normalized.contains("permission denied")
                || normalized.contains("connection refused")
                || normalized.contains("no such file")
                || normalized.contains("not found")
                || normalized.contains("failed")
                || normalized.contains("error");
    }

    /**
     * SHA-1 哈希，用于生成 memory_key 去重键。
     * 失败时降级为 hashCode，保证不抛异常。
     */
    private String sha1(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    /** 记忆打分内部记录类，用于排序 */
    /**
     * 一次打分的完整结果。
     * <p>
     * 除融合总分外还带上 {@code coverage} 与 {@code importance}：阈值判断要看单信号
     * （见 {@link #passGate}），而只剩一个总分的话，过滤阶段就没法区分
     * "命中很准但旧"与"重要但弱相关"这两种情况。
     *
     * @param memory     记忆本体
     * @param score      融合总分（0~1）
     * @param coverage   关键词 IDF 加权覆盖率（0~1，无关键词时为 0）
     * @param importance 记忆自身重要度（0~1）
     */
    private record MemoryScore(LongTermMemoryEntity memory, double score, double coverage, double importance) {}

    /** 中英文停用词集合：在关键词提取时被过滤，避免无意义词干扰匹配 */
    private static final Set<String> STOP_WORDS = new LinkedHashSet<>(List.of(
            "请", "帮", "一下", "这个", "那个", "现在", "需要", "进行", "继续", "问题", "情况",
            "the", "and", "for", "with", "that", "this", "from", "into", "have", "has"
    ));
}

