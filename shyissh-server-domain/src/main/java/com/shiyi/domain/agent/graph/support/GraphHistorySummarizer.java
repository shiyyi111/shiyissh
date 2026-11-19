package com.shiyi.domain.agent.graph.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 会话历史摘要器：把"被三道闸丢掉的老消息"压成几句话，重新塞回上下文。
 * <p>
 * 解决的是"丢历史 = 失忆"这个具体问题：
 * <pre>
 *   用户第 1 轮：重启后 nginx 起不来，帮我看看
 *   用户第 2~6 轮：（一路排查，历史超预算）
 *   用户第 7 轮：那按你说的改完还不行呢？
 * </pre>
 * 第 1 轮那句话早被 {@code trimToRecentTurns} 丢掉了，模型看到的是"改完还不行"却不知道
 * "改的是什么"。摘要器把丢掉的部分压成"用户反馈 nginx 重启失败、已确认 80 端口被占用"，
 * 用两三百 token 换回语义连续性。
 * <p>
 * 四条设计约束：
 * <ul>
 *   <li><b>失败即降级</b>：摘要调模型可能超时/报错，失败返回 {@code null}，主链路继续跑；
 *       返回 {@code null} 与返回空串是两件事——前者是"该有但没有"（调用方要记
 *       {@code summarySkipped}），后者是"本来就不需要"；</li>
 *   <li><b>不足阈值不调</b>：只丢了一两条没必要花一次模型调用（{@link #MIN_DROPPED_MESSAGES}）；</li>
 *   <li><b>保留最近的、舍弃最远的</b>：摘要的作用是弥合"窗口起点之前"的上下文，
 *       紧邻窗口的那几条与当前问题最相关，超长时必须从最早处截断；</li>
 *   <li><b>只做压缩不做推断</b>：prompt 明确禁止编造，宁可少写也不能凭空补细节 ——
 *       摘要会被当成事实带进后续推理。</li>
 * </ul>
 *
 * @author shiyi
 */
@Service
public class GraphHistorySummarizer {

    private static final Logger log = LoggerFactory.getLogger(GraphHistorySummarizer.class);

    /**
     * 少于这么多条被丢弃时不做摘要：省一次模型调用，收益也不明显。
     * <p>
     * 取 2 而不是更大值：优先级裁剪经常一次只挑掉一两条，阈值设大了这些丢弃就永远进不了摘要，
     * 长期累积成"静默失忆"；2 条约等于至少一轮问答，是这个功能值得跑一次模型的最小粒度。
     */
    public static final int MIN_DROPPED_MESSAGES = 2;

    /**
     * 被丢弃内容的字符总量下限：少于这么多字就不摘要。
     * <p>
     * 条数够但每条都很短（"好的""继续"）时，摘要的压缩收益接近于零，却要付一次模型调用的
     * 延迟和 token；真正值得摘要的是"被裁掉了整段排查过程"。两个门槛同时满足才跑。
     */
    private static final int MIN_DROPPED_CHARS = 800;

    /** 送进摘要模型的原文上限（字符），防止一次摘要本身就把上下文撑爆。 */
    private static final int MAX_INPUT_CHARS = 6000;

    /**
     * 增量模式下"新增滑出片段"的字符下限。
     * <p>
     * 有旧摘要时，一两条短消息的增量收益很低（合并后的摘要几乎不变），却要付一次模型调用；
     * 低于这个量就本轮不更新、也不推进游标，下轮把这段和更晚的片段一起算。
     */
    private static final int MIN_INCREMENT_CHARS = 400;

    /** 增量更新指令：在旧摘要基础上合并新片段，而不是重写。 */
    private static final String SUMMARY_UPDATE_INSTRUCTION = """
            你是运维对话的会话摘要器。下面给出「此前摘要」与「新增被丢弃的对话」，请把两者合并成一份更新后的摘要。
            要求：
            1. 保留此前摘要里仍然成立的事实，不要丢失；新增信息补充进去，重复的内容合并。
            2. 只保留事实：涉及的主机/服务/端口/路径、用户反映的现象、报错原文中的关键信息、已执行的操作、已得出的结论。
            3. 不要编造原文没有的信息；不确定的内容直接不写。
            4. 用第三人称陈述，例如「用户反映 nginx 重启失败，已确认 80 端口被占用」。
            5. 合并后的摘要控制在 3~6 句话，直接输出正文，不要加标题、不要分点编号。
            """;

    /** 摘要输出上限（token），对应约两三百汉字。 */
    private static final int MAX_SUMMARY_TOKENS = 400;

    /** 进程内缓存容量：同一批被丢弃消息在同一轮里只会算一次。 */
    private static final int CACHE_CAPACITY = 64;

    private static final String TRUNCATED_HINT = "…（更早的内容已省略）";

    private static final String SUMMARY_INSTRUCTION = """
            你是运维对话的会话摘要器。把下面这段"较早的对话"压缩成 3~5 句话的要点，供后续对话参考。
            要求：
            1. 只保留事实：涉及的主机/服务/端口/路径、用户反映的现象、报错原文中的关键信息、已经执行过的操作、已经得出的结论。
            2. 去掉寒暄、重复确认、与故障无关的闲聊。
            3. 不要编造原文没有的信息；不确定的内容直接不写。
            4. 用第三人称陈述，例如「用户反映 nginx 重启失败，已确认 80 端口被占用」。
            5. 直接输出要点正文，不要加标题、不要分点编号。
            """;

    /** 用 ObjectProvider 延迟取模型：装配期 ChatModel 可能还没就绪，且单测可以直接 new。 */
    private final ObjectProvider<ChatModel> chatModelProvider;

    /**
     * 摘要缓存（LRU）：key = 会话 + 被丢弃消息的首尾指纹。
     * <p>
     * 为什么值得加：同一批 dropped 在一轮内是稳定的，下一轮只会"更老"（同一批 + 新增几条），
     * 不缓存就等于每轮把同样的输入重算一次；单次摘要要走一次模型调用，首字节延迟几百毫秒到数秒、
     * 还额外烧 token。单实例部署，进程内缓存即可，不需要引入 Redis。
     */
    private final Map<String, String> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > CACHE_CAPACITY;
                }
            });

    public GraphHistorySummarizer(ObjectProvider<ChatModel> chatModelProvider) {
        this.chatModelProvider = chatModelProvider;
    }

    /**
     * 把被裁掉的历史压成一段摘要（无会话维度的旧签名，不参与缓存）。
     *
     * @param dropped 被三道闸丢掉的消息（时间正序，可为空）
     * @return 摘要文本；不需要摘要时返回空串，失败时返回 {@code null}
     */
    public String summarize(List<Message> dropped) {
        return summarize(null, dropped);
    }

    /**
     * 把被裁掉的历史压成一段摘要。
     *
     * @param sessionId 会话 id，用于缓存（可为 null）
     * @param dropped   被三道闸丢掉的消息（时间正序，可为空）
     * @return 摘要文本；不需要摘要时返回空串，失败时返回 {@code null}
     */
    public String summarize(String sessionId, List<Message> dropped) {
        return summarize(sessionId, dropped, null);
    }

    /**
     * 把"新增滑出片段"合并进已有摘要（增量模式）。
     * <p>
     * 与全量模式的区别只在输入构造：全量是"把这段历史压成摘要"，
     * 增量是"此前摘要 + 新增片段 → 更新后的摘要"。门槛也不同：增量只要新增内容够长就跑，
     * 不再要求总条数（否则游标永远推不动）。
     *
     * @param sessionId       会话 id，用于缓存（可为 null）
     * @param dropped         本次新滑出的消息（时间正序，可为空）
     * @param previousSummary 库里已有的摘要；为 null/空表示全量模式
     * @return 摘要文本；不需要更新时返回空串，失败（无模型/调用异常）时返回 {@code null}
     */
    public String summarize(String sessionId, List<Message> dropped, String previousSummary) {
        boolean incremental = previousSummary != null && !previousSummary.isBlank();
        if (dropped == null || dropped.isEmpty()) {
            return "";
        }
        if (!incremental) {
            if (dropped.size() < MIN_DROPPED_MESSAGES) {
                return "";
            }
            if (totalChars(dropped) < MIN_DROPPED_CHARS) {
                return "";
            }
        } else if (totalChars(dropped) < MIN_INCREMENT_CHARS) {
            return "";
        }
        String cacheKey = cacheKey(sessionId, dropped, previousSummary);
        if (cacheKey != null) {
            String cached = cache.get(cacheKey);
            if (cached != null) {
                log.debug("[summary] 命中摘要缓存 sessionId={}, dropped={}", sessionId, dropped.size());
                return cached;
            }
        }
        String transcript = renderTranscript(dropped);
        if (transcript.isBlank()) {
            return "";
        }
        if (incremental) {
            // 旧摘要必须进来：否则每轮只看到"最新滑出的几条"，早期事实会被越压越少
            transcript = "【此前摘要】\n" + previousSummary.strip() + "\n\n【新增被丢弃的对话】\n" + transcript;
        }
        ChatModel chatModel = chatModelProvider == null ? null : chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            // 没有模型不是"不需要摘要"，而是"想要但没条件"，调用方要据此标记 summarySkipped
            log.debug("[summary] 没有可用 ChatModel，历史摘要跳过");
            return null;
        }
        try {
            List<Message> promptMessages = new ArrayList<>();
            promptMessages.add(new SystemMessage(incremental ? SUMMARY_UPDATE_INSTRUCTION : SUMMARY_INSTRUCTION));
            promptMessages.add(new UserMessage(transcript));
            Prompt prompt = new Prompt(promptMessages, OpenAiChatOptions.builder()
                    .temperature(0.0)
                    .maxTokens(MAX_SUMMARY_TOKENS)
                    .build());
            String summary = chatModel.call(prompt).getResult().getOutput().getText();
            if (summary == null || summary.isBlank()) {
                log.warn("[summary] 模型返回空摘要，本轮按无摘要继续 sessionId={}", sessionId);
                return null;
            }
            String result = summary.strip();
            if (cacheKey != null) {
                cache.put(cacheKey, result);
            }
            log.info("[summary] 历史摘要完成，丢弃 {} 条 → 摘要 {} 字",
                    dropped.size(), result.length());
            return result;
        } catch (Exception e) {
            // 摘要失败不影响主流程：宁可这轮"少一段摘要"，也不能让对话报错
            log.warn("[summary] 历史摘要失败，本轮按无摘要继续: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 拼成 "用户：... / 助手：..." 的纯文本。
     * <p>
     * 超长时<b>从最早处截断</b>：摘要要覆盖的是"紧邻窗口之前"的那段，离当前问题越近越该保留；
     * 反过来保留开头会得到"聊了很久之后最相关的内容全没了"的摘要。
     */
    private String renderTranscript(List<Message> dropped) {
        List<String> lines = new ArrayList<>();
        int budget = MAX_INPUT_CHARS;
        boolean truncated = false;
        // 重复内容只留最近一次：重试同一个命令、反复贴同一段日志是常见情况，
        // 全留着会把预算耗在重复信息上，把真正不同的那几条挤出摘要输入。
        Map<String, Integer> repeated = new LinkedHashMap<>();
        for (int i = dropped.size() - 1; i >= 0; i--) {
            Message message = dropped.get(i);
            String text = message == null ? null : message.getText();
            if (text == null || text.isBlank()) {
                truncated = true;
                continue;
            }
            String fingerprint = fingerprint(text);
            if (repeated.containsKey(fingerprint)) {
                repeated.merge(fingerprint, 1, Integer::sum);
                continue;
            }
            repeated.put(fingerprint, 0);
            String line = (message instanceof UserMessage ? "用户：" : "助手：") + text.strip();
            if (line.length() > budget) {
                // 单条就超预算：保结尾（话题通常落在最近一句），前面舍掉
                line = line.substring(line.length() - budget);
                truncated = true;
                budget = 0;
            } else {
                budget -= line.length();
            }
            lines.add(line);
            if (budget <= 0) {
                truncated = true;
                break;
            }
        }
        if (lines.isEmpty()) {
            return "";
        }
        Collections.reverse(lines);
        StringBuilder sb = new StringBuilder();
        if (truncated) {
            sb.append(TRUNCATED_HINT).append('\n');
        }
        int repeatedCount = repeated.values().stream().mapToInt(Integer::intValue).sum();
        if (repeatedCount > 0) {
            sb.append("（另有 ").append(repeatedCount).append(" 条重复输出已省略）").append('\n');
        }
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString().strip();
    }

    /** 内容指纹：折叠空白后取前 120 字，用来识别"同一段输出又贴了一遍"。 */
    private static String fingerprint(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 120 ? flat : flat.substring(0, 120);
    }

    /**
     * 缓存键：会话 + 条数 + 首尾文本指纹。
     * <p>
     * 不用整段内容做 key（长文本哈希成本高一倍）；首尾 + 条数已经能区分"同一批"与"又多丢了几条"。
     */
    private String cacheKey(String sessionId, List<Message> dropped, String previousSummary) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String first = textOf(dropped.get(0));
        String last = textOf(dropped.get(dropped.size() - 1));
        // 旧摘要必须进 key：同样是"新增 2 条"，但旧摘要变了之后结果完全不同
        return sessionId + '|' + dropped.size() + '|'
                + Objects.hash(first, last, previousSummary == null ? "" : previousSummary);
    }

    /** 被丢弃消息的字符总量，用于判断"这点内容值不值得走一次模型"。 */
    private static int totalChars(List<Message> dropped) {
        int total = 0;
        for (Message message : dropped) {
            String text = message == null ? null : message.getText();
            if (text != null) {
                total += text.length();
            }
        }
        return total;
    }

    private String textOf(Message message) {
        String text = message == null ? null : message.getText();
        return text == null ? "" : text;
    }
}
