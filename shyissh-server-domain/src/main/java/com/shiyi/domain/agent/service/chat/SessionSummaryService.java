package com.shiyi.domain.agent.service.chat;

import com.shiyi.domain.agent.adapter.repository.IChatSessionSummaryRepository;
import com.shiyi.domain.agent.graph.support.GraphHistorySummarizer;
import com.shiyi.domain.agent.graph.support.GraphSession;
import com.shiyi.domain.agent.model.entity.ChatSessionSummaryEntity;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话摘要服务：把"滑出上下文窗口的历史"做成一条可复用、可增量的摘要。
 * <p>
 * 为什么需要它（旧实现的问题）：{@code GraphHistorySummarizer.summarize(sessionId, dropped)}
 * 每轮都拿"当前被丢弃的全部消息"重新算一次——同一批内容反复调用模型，且上轮的摘要
 * 不会带进下轮，等于每轮都从零开始压缩，早期事实越压越少。
 * <p>
 * 现在的语义（一条会话一行 {@code chat_session_summary}）：
 * <ol>
 *   <li>本轮<b>没有新滑出</b>（{@code droppedMaxId <= covered_until_id}）：直接用库里的摘要，<b>0 次模型调用</b>；</li>
 *   <li>有新滑出：拿"旧摘要 + 新增片段"做<b>一次</b>增量摘要，游标推进到本轮 {@code droppedMaxId}；</li>
 *   <li>增量做多了会漂移：摘要超过 {@link #MAX_SUMMARY_CHARS} 字或累计 {@link #COMPACT_EVERY} 次后，
 *       改用"全量被丢弃消息"重算一次（压实），并清零计数；</li>
 *   <li>并发安全：写入是 {@code UPDATE ... WHERE covered_until_id = 旧游标}，更新 0 行说明别的请求
 *       已经推进过，本次放弃覆盖（幂等），返回库里那份。</li>
 * </ol>
 *
 * @author shiyi
 */
@Service
public class SessionSummaryService {

    private static final Logger log = LoggerFactory.getLogger(SessionSummaryService.class);

    /** 摘要超过这么多字就该压实：再长下去会占掉本该留给对话的预算。 */
    public static final int MAX_SUMMARY_CHARS = 400;

    /** 连续增量这么多次就该压实一次：增量只看到"旧摘要 + 新片段"，误差会累积。 */
    public static final int COMPACT_EVERY = 8;

    @Resource
    private IChatSessionSummaryRepository summaryRepository;

    @Resource
    private GraphHistorySummarizer historySummarizer;

    /**
     * 摘要向量化（best-effort）：让"下次开新会话问上次的事"能跨会话召回。
     * <p>
     * 用 {@code ObjectProvider} 是为了在 {@code app.rag.enabled=false} 的环境（含多数单测）
     * 里也能装配本服务。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.shiyi.domain.agent.service.index.VectorIndexWorker vectorIndexWorker;

    /**
     * 计算本轮要用的会话摘要。
     *
     * @param sessionId 会话 ID
     * @param userId    会话归属用户（跨会话回忆按它隔离）
     * @param loaded    本轮历史装载结果（带被丢弃消息及其 id）
     * @return 摘要文本；不需要摘要时返回空串；"该有但拿不到"（无模型/调用失败）返回 {@code null}
     */
    public String summarize(String sessionId, String userId, GraphSession.LoadedHistory loaded) {
        if (sessionId == null || sessionId.isBlank() || loaded == null) {
            return "";
        }
        ChatSessionSummaryEntity stored = summaryRepository.findBySessionId(sessionId);
        long cursor = stored == null || stored.getCoveredUntilId() == null ? 0L : stored.getCoveredUntilId();
        long droppedMaxId = loaded.droppedMaxId();
        String storedSummary = stored == null || stored.getSummary() == null ? "" : stored.getSummary();
        if (droppedMaxId <= cursor) {
            // 没有新滑出的内容：复用库里的摘要，不花模型调用
            return storedSummary;
        }
        List<Message> fresh = messagesAfter(loaded, cursor);
        if (fresh.isEmpty()) {
            return storedSummary;
        }
        int increments = stored == null || stored.getIncrements() == null ? 0 : stored.getIncrements();
        boolean compact = !storedSummary.isEmpty()
                && (storedSummary.length() > MAX_SUMMARY_CHARS || increments >= COMPACT_EVERY);
        List<Message> input = compact ? loaded.dropped() : fresh;
        // 压实只是把输入换成更长的范围，不是丢掉旧摘要：旧摘要里记着本次窗口之前的内容，
        // 传 null 会让压实把这段内容整块丢掉（越压越少，最后只剩最近几轮）。
        String previous = storedSummary.isEmpty() ? null : storedSummary;
        String summary = historySummarizer == null
                ? null
                : historySummarizer.summarize(sessionId, input, previous);
        if (summary == null) {
            // "该有但拿不到"：本轮按无摘要继续，游标不推进（下一轮把这段和新片段一起算）
            return null;
        }
        if (summary.isBlank()) {
            // 没达到增量门槛：保持库里的摘要，同样不推进游标
            return storedSummary;
        }
        String result = summary.strip();
        ChatSessionSummaryEntity entity = new ChatSessionSummaryEntity();
        entity.setSessionId(sessionId);
        entity.setUserId(userId == null ? "" : userId);
        entity.setSummary(result);
        long newCursor = safeCursor(loaded, droppedMaxId);
        entity.setCoveredUntilId(newCursor);
        entity.setIncrements(compact ? 0 : increments + 1);
        boolean saved = stored == null
                ? summaryRepository.insertIfAbsent(entity)
                : summaryRepository.updateIfCursor(entity, cursor);
        if (!saved) {
            // 并发：另一个请求已经推进了游标并写入了它的结果，这里以库里为准
            ChatSessionSummaryEntity latest = summaryRepository.findBySessionId(sessionId);
            String latestSummary = latest == null || latest.getSummary() == null ? result : latest.getSummary();
            log.info("[summary] 游标已被并发请求推进，沿用库中摘要 sessionId={}", sessionId);
            return latestSummary;
        }
        log.info("[summary] op={} sessionId={} dropped={} newCursor={} increments={} chars={}",
                compact ? "compact" : "incremental", sessionId, input.size(), newCursor,
                entity.getIncrements(), result.length());
        indexForCrossSessionRecall(sessionId, entity.getUserId(), result);
        return result;
    }

    /**
     * 摘要写好后顺带写一份向量索引，供跨会话回忆。
     * <p>
     * 失败只记日志：摘要已经落库，跨会话"搜不到"是能力降级，不该让本轮对话失败。
     */
    private void indexForCrossSessionRecall(String sessionId, String userId, String summary) {
        if (vectorIndexWorker == null) {
            return;
        }
        try {
            vectorIndexWorker.enqueueSummary(sessionId, userId, summary);
        } catch (Exception e) {
            log.warn("[summary] 摘要索引失败（不影响本次对话）sessionId={}：{}", sessionId, e.getMessage());
        }
    }

    /**
     * 取"游标之后"新滑出的消息。
     * <p>
     * 按 {@code droppedIds} 与 {@code dropped} 的逐条对齐过滤；没有 id 信息（旧调用/测试）
     * 时退化为全量，宁可多算一次也不漏内容。
     */
    /**
     * 计算本轮可以安全推进到的游标。
     * <p>
     * 不能直接用 droppedMaxId：reducer 会保留少量高优先级消息，它们的 id 可能落在
     * 本次丢弃范围内（散点保留）。游标一旦跳到 max，那些被保留的消息以后真正滑出时，
     * messagesAfter 会按 id 大于 cursor 过滤掉它，这段内容就永远进不了摘要。
     * 这里按丢弃的 id 是否连续判断：出现断档时只推进到断档前一个 id。
     */
    private long safeCursor(GraphSession.LoadedHistory loaded, long fallback) {
        List<Long> ids = loaded.droppedIds();
        if (ids == null || ids.isEmpty()) {
            return fallback;
        }
        List<Long> sorted = new ArrayList<>(ids);
        sorted.sort(Long::compareTo);
        long safe = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            long previous = sorted.get(i - 1);
            long current = sorted.get(i);
            if (current != previous + 1) {
                return Math.max(0L, previous);
            }
            safe = current;
        }
        return Math.max(safe, fallback);
    }

    private List<Message> messagesAfter(GraphSession.LoadedHistory loaded, long cursor) {
        List<Message> dropped = loaded.dropped();
        List<Long> ids = loaded.droppedIds();
        if (dropped == null || dropped.isEmpty()) {
            return List.of();
        }
        if (ids == null || ids.size() != dropped.size()) {
            return dropped;
        }
        List<Message> fresh = new ArrayList<>(dropped.size());
        for (int i = 0; i < dropped.size(); i++) {
            Long id = ids.get(i);
            long value = id == null ? 0L : id;
            if (value > cursor) {
                fresh.add(dropped.get(i));
            }
        }
        return fresh;
    }
}