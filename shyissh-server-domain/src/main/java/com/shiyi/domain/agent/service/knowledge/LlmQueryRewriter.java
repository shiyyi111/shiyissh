package com.shiyi.domain.agent.service.knowledge;

import com.shiyi.domain.agent.model.valobj.properties.RagProperties;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 查询改写：把用户的口语化问题改写成"检索友好"的一句话。
 * <p>
 * 和 {@link QueryNormalizer} 的分工：归一化是<b>确定性</b>的（同义词表、强信号抽取），改写是<b>推断</b>的
 * （"内存爆了"→"内存不足 OOM"这种词典里没有的映射）。前者永远先跑、不花钱；后者是它在首轮召回不够时才补的一枪。
 * <p>
 * 为什么不一上来就改写：一次模型调用 300~800ms，而运维问题里一大半用同义词表 + 关键词就能命中；
 * 为了少数口语化查询给每次检索都加一次模型调用，延迟和成本都不划算。
 * <p>
 * 所以触发条件写在调用方：首轮命中条数 < {@code trigger-min-hits} 且没改写过的查询才走这里。
 * 所有失败都返回 null（静默降级），检索绝不能因为改写失败而失败。
 *
 * @author shiyi
 */
@Service
public class LlmQueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(LlmQueryRewriter.class);

    /** 缓存上限：查询是开放集合，但真正高频的也就那么几十个，超过就整体清掉（比实现 LRU 更简单且够用）。 */
    private static final int CACHE_MAX = 512;

    /**
     * 模型输出长度上限：改写结果是一条查询，不是解释。
     * 取 80 而不是 50 左右，是因为实测改写会把"原词 + 同义术语"都写上（"内存爆了 内存不足 OOM out of memory"），
     * 太短容易把最后的英文术语截成半个词（"DNS" 被截成 "DN" 这种），反而污染检索。
     */
    private static final int MAX_REWRITE_CHARS = 80;

    private static final String SYSTEM_PROMPT = """
            你是运维知识库的检索助手。把用户的问题改写成一条适合全文检索的查询。

            要求：
            1. 保留原文里所有专有名词、主机名、IP、端口、错误码、命令名，一个都不要替换或删除。
            2. 补全口语化表达背后的运维术语，例如"内存爆了"→"内存不足 OOM"、"连不上"→"连接被拒绝 connection refused"。
            3. 可以补充同义说法，但不要把问题扩展成多个不相关的方向。
            4. 只输出改写后的查询本身：不要解释、不要加引号、不要换行、不要以"查询："开头。
            """;

    @Resource
    private RagProperties ragProperties;

    /** 用 ObjectProvider：没有配模型时应用照常启动，改写能力自动降级为"不做"。 */
    @Resource
    private ObjectProvider<ChatModel> chatModelProvider;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(String rewritten, long expireAtMillis) {
    }

    /** 是否启用改写（{@code app.rag.rewrite.enabled}）。 */
    public boolean available() {
        return ragProperties.getRewrite().isEnabled();
    }

    /**
     * 改写查询。
     *
     * @param query 归一化之后的查询（已经是稳定说法，改写只在它基础上补术语）
     * @return 改写结果；未启用、模型不可用、超时、输出为空或与原句相同都返回 {@code null}
     */
    public String rewrite(String query) {
        if (!available() || query == null || query.isBlank()) {
            return null;
        }
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(query);
        if (cached != null) {
            if (cached.expireAtMillis() > now) {
                return cached.rewritten();
            }
            cache.remove(query);
        }

        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return null;
        }
        try {
            long timeout = ragProperties.getRewrite().getTimeoutMs();
            String raw = CompletableFuture
                    .supplyAsync(() -> callModel(chatModel, query))
                    .get(timeout, TimeUnit.MILLISECONDS);
            String cleaned = sanitize(raw);
            if (cleaned.isEmpty() || cleaned.equals(query)) {
                return null;
            }
            if (cache.size() > CACHE_MAX) {
                cache.clear();
            }
            long ttl = Math.max(1L, ragProperties.getRewrite().getCacheMinutes()) * 60_000L;
            cache.put(query, new CacheEntry(cleaned, now + ttl));
            log.info("[knowledge] 查询改写：\"{}\" → \"{}\"", query, cleaned);
            return cleaned;
        } catch (Exception e) {
            // 超时/限流/网络问题都走这里：记一条 debug 就够了，检索会继续用原查询
            log.debug("[knowledge] 查询改写失败，沿用原查询：{}", e.getMessage());
            return null;
        }
    }

    private String callModel(ChatModel chatModel, String query) {
        Prompt prompt = new Prompt(List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(query)));
        ChatResponse response = chatModel.call(prompt);
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    /** 清洗模型输出：取第一行、去掉引号与常见前缀、限制长度。 */
    private static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.strip();
        int lineBreak = text.indexOf('\n');
        if (lineBreak > 0) {
            text = text.substring(0, lineBreak).strip();
        }
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() > 2) {
            text = text.substring(1, text.length() - 1).strip();
        }
        if (text.startsWith("「") && text.endsWith("」") && text.length() > 2) {
            text = text.substring(1, text.length() - 1).strip();
        }
        for (String prefix : List.of("查询：", "查询:", "改写后：", "改写后:")) {
            if (text.startsWith(prefix)) {
                text = text.substring(prefix.length()).strip();
            }
        }
        if (text.length() <= MAX_REWRITE_CHARS) {
            return text;
        }
        // 截断只截到词边界：中文没有空格，硬截会切出半个词；英文术语被切一半（检索时反而多出一个噪声词）
        String cut = text.substring(0, MAX_REWRITE_CHARS);
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace > MAX_REWRITE_CHARS / 2) {
            cut = cut.substring(0, lastSpace);
        }
        return cut.strip();
    }
}