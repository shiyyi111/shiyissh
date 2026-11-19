package com.shiyi.domain.agent.service.index;

import com.shiyi.domain.agent.adapter.repository.ILongTermMemoryRepository;
import com.shiyi.domain.agent.adapter.repository.IWikiRepository;
import com.shiyi.domain.agent.service.chat.SummaryVectorService;
import com.shiyi.domain.agent.service.knowledge.impl.KnowledgeIndexer;
import com.shiyi.domain.agent.service.memory.MemoryVectorService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

/**
 * 向量索引的单消费者 worker：一个专用线程阻塞在队列上，把"待向量化"的任务排队处理。
 * <p>
 * <b>为什么不是定时轮询</b>：定时任务的问题是"新数据要等下一个周期"，且空转时也在占调度线程；
 * 这里由生产者推、消费者阻塞等，写入后通常在 1 秒内完成索引，没有扫描窗口。
 * <p>
 * <b>为什么只有一个线程</b>：embedding 是外部调用（200~300ms），单线程已经把并发压到 1，
 * 不会和召回路径争抢配额；单线程的顺序处理也让"同一 key 连续更新"天然幂等——
 * 处理时以数据库当前内容为准，后写的覆盖先写的。
 * <p>
 * <b>失败与兜底</b>：
 * <ul>
 *   <li>单批失败按任务退避重试（1s / 2s / 4s），三次仍失败则放弃并记 warn；</li>
 *   <li>记忆的失败同时累加 {@code embedding_attempts}，达上限后由数据层标记不再重试；</li>
 *   <li>队列满时不阻塞生产者——任务留在数据库的 {@code embedding_status=0}，由下次启动排空或手动重建兜底。</li>
 * </ul>
 * <p>
 * 启动时先排空 MySQL 里遗留的待索引记忆（进程崩溃、上次队列满、embedding 服务不可用都会留下），
 * 再进入队列循环：这一步让"没有任何周期性任务"也依然不会丢索引。
 *
 * @author shiyi
 */
@Component
public class VectorIndexWorker {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexWorker.class);

    /** 队列容量：写记忆是低频操作，500 足够；满了也不丢数据，只是晚一点由启动排空处理。 */
    private static final int QUEUE_CAPACITY = 500;

    /** 一次最多从队列里再取 9 条（加 take 到的 1 条，正好凑一批 embedBatch 的上限 10）。 */
    private static final int MAX_DRAIN = 9;

    /** 单批失败最多重试次数（不含首次）。 */
    private static final int MAX_ATTEMPTS = 3;

    /** 第一次重试的退避时间默认值，之后翻倍（可配置，测试里会压到毫秒级）。 */
    private static final long DEFAULT_RETRY_BASE_MS = 1_000L;

    /** 启动后等待默认值：给数据源、MyBatis、向量库客户端留出初始化时间。 */
    private static final long DEFAULT_STARTUP_WAIT_MS = 3_000L;

    /** 启动排空最多跑 20 轮，避免异常数据导致启动线程长期占着。 */
    private static final int STARTUP_DRAIN_ROUNDS = 20;

    /** 任务类型：记忆、wiki、会话摘要。 */
    public enum Kind { MEMORY, WIKI, SUMMARY }

    /**
     * 一条待索引任务。
     *
     * @param kind      类型
     * @param id        业务主键（SUMMARY 用 0，靠 sessionId 定位）
     * @param sessionId 会话 ID（仅 SUMMARY 用）
     * @param userId    用户 ID（仅 SUMMARY 用）
     * @param text      摘要正文（仅 SUMMARY 用，落库后无需再回表）
     */
    public record Task(Kind kind, long id, String sessionId, String userId, String text) {

        String key() {
            return kind + ":" + (kind == Kind.SUMMARY ? sessionId : String.valueOf(id));
        }
    }

    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    /** 重试计数：按任务 key（kind + 主键）累计，成功后清零。 */
    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Thread consumer;

    private ScheduledExecutorService retryScheduler;

    @Resource
    MemoryVectorService memoryVectorService;

    @Resource
    KnowledgeIndexer knowledgeIndexer;

    @Resource
    SummaryVectorService summaryVectorService;

    @Resource
    ILongTermMemoryRepository longTermMemoryRepository;

    @Resource
    IWikiRepository wikiRepository;

    /** 记忆向量索引总开关：关掉后写入不入队，召回退回关键词候选池。 */
    @Value("${app.rag.memory-index.enabled:true}")
    boolean memoryIndexEnabled;

    /** 启动排空时每批处理的条数。 */
    @Value("${app.rag.memory-index.batch-size:50}")
    private int drainBatchSize;

    /** 消费线程启动后的等待时间（毫秒），配置项便于测试压到 0。 */
    @Value("${app.rag.vector-index.startup-wait-ms:3000}")
    long startupWaitMs = DEFAULT_STARTUP_WAIT_MS;

    /** 失败重试的首次退避（毫秒），之后翻倍，最多重试 {@link #MAX_ATTEMPTS} 次。 */
    @Value("${app.rag.vector-index.retry-base-ms:1000}")
    long retryBaseMs = DEFAULT_RETRY_BASE_MS;

    /**
     * 记忆写入后入队（非阻塞）。
     *
     * @param id 记忆主键
     */
    public void enqueueMemory(long id) {
        if (!memoryIndexEnabled) {
            return;
        }
        offer(new Task(Kind.MEMORY, id, null, null, null));
    }

    /**
     * wiki 写入后入队（非阻塞）。
     *
     * @param id wiki 主键
     */
    public void enqueueWiki(long id) {
        offer(new Task(Kind.WIKI, id, null, null, null));
    }

    /**
     * 会话摘要落库后入队（非阻塞）。
     *
     * @param sessionId 会话 ID
     * @param userId    用户 ID
     * @param summary   摘要正文
     */
    public void enqueueSummary(String sessionId, String userId, String summary) {
        if (sessionId == null || sessionId.isBlank() || summary == null || summary.isBlank()) {
            return;
        }
        offer(new Task(Kind.SUMMARY, 0L, sessionId, userId, summary));
    }

    /** 当前排队长度，供测试与运维观察。 */
    public int queueSize() {
        return queue.size();
    }

    private void offer(Task task) {
        if (!queue.offer(task)) {
            log.warn("[vector-index] 队列已满（{}），{} 暂不索引，等启动排空或手动重建", QUEUE_CAPACITY, task.key());
        }
    }

    @PostConstruct
    public void start() {
        retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "vector-index-retry");
            thread.setDaemon(true);
            return thread;
        });
        running.set(true);
        consumer = new Thread(this::loop, "vector-index-worker");
        consumer.setDaemon(true);
        consumer.start();
        log.info("[vector-index] worker 已启动 queueCapacity={}", QUEUE_CAPACITY);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (consumer != null) {
            consumer.interrupt();
        }
        if (retryScheduler != null) {
            retryScheduler.shutdownNow();
        }
        int left = queue.size();
        if (left > 0) {
            log.warn("[vector-index] worker 已停止，队列里还有 {} 条未处理：记忆会由启动排空补齐，"
                    + "wiki 由启动补扫重新发现；若重启后仍缺失，请手动重建索引", left);
        } else {
            log.info("[vector-index] worker 已停止，队列已清空");
        }
    }

    /**
     * 应用上下文就绪后排空一次遗留任务。
     * <p>
     * 这里不重新入队"待索引 id"再逐条处理，而是复用 {@link MemoryVectorService#indexPending(int)}：
     * 它本身就是"取待索引 → 向量化 → 标记状态"的完整闭环，失败也会累加 attempts，
     * 比在启动路径里重写一遍状态机更可靠。
     */
    @EventListener(ContextRefreshedEvent.class)
    public void drainOnStartup() {
        if (!memoryIndexEnabled) {
            return;
        }
        try {
            int batch = drainBatchSize <= 0 ? MemoryVectorService.DEFAULT_INDEX_BATCH : drainBatchSize;
            int total = 0;
            for (int round = 0; round < STARTUP_DRAIN_ROUNDS; round++) {
                int indexed = memoryVectorService.indexPending(batch);
                total += indexed;
                if (indexed < batch) {
                    break;
                }
            }
            if (total > 0) {
                log.info("[vector-index] 启动排空完成 indexed={}", total);
            }
            drainWikiOnStartup(batch);
        } catch (Exception e) {
            log.warn("[vector-index] 启动排空失败（不影响启动）：{}", e.getMessage(), e);
        }
    }

    /**
     * 补扫 wiki 的待索引条目。
     * <p>
     * wiki 没有独立的待索引状态列，丢失的队列任务只能靠全量游标扫描补：
     * 这里按 id 游标把库里的条目重新入队，覆盖写同 id 向量是幂等的。
     */
    private void drainWikiOnStartup(int batch) {
        if (wikiRepository == null) {
            return;
        }
        long afterId = 0L;
        int scanned = 0;
        try {
            for (int round = 0; round < STARTUP_DRAIN_ROUNDS; round++) {
                List<com.shiyi.domain.agent.model.entity.WikiEntryEntity> page =
                        wikiRepository.listForIndex(afterId, batch);
                if (page == null || page.isEmpty()) {
                    break;
                }
                List<Long> ids = new ArrayList<>(page.size());
                for (com.shiyi.domain.agent.model.entity.WikiEntryEntity entry : page) {
                    ids.add(entry.getId());
                    afterId = entry.getId();
                }
                knowledgeIndexer.indexWikiByIds(ids);
                scanned += ids.size();
                if (page.size() < batch) {
                    break;
                }
            }
            if (scanned > 0) {
                log.info("[vector-index] 启动补扫 wiki 完成 scanned={}", scanned);
            }
        } catch (Exception e) {
            log.warn("[vector-index] 启动补扫 wiki 失败（不影响启动）：{}", e.getMessage(), e);
        }
    }

    private void loop() {
        try {
            Thread.sleep(startupWaitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        while (running.get()) {
            try {
                Task first = queue.take();
                List<Task> batch = new ArrayList<>(MAX_DRAIN + 1);
                batch.add(first);
                queue.drainTo(batch, MAX_DRAIN);
                process(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 单批异常不能打死消费者：记录后继续取下一条
                log.warn("[vector-index] 处理异常：{}", e.getMessage(), e);
            }
        }
    }

    private void process(List<Task> batch) {
        List<Task> memories = new ArrayList<>();
        List<Task> wikis = new ArrayList<>();
        List<Task> summaries = new ArrayList<>();
        for (Task task : batch) {
            switch (task.kind()) {
                case MEMORY -> memories.add(task);
                case WIKI -> wikis.add(task);
                case SUMMARY -> summaries.add(task);
            }
        }
        if (!memories.isEmpty()) {
            List<Long> ids = memories.stream().map(Task::id).toList();
            runGroup(memories, () -> memoryVectorService.indexByIds(ids));
        }
        if (!wikis.isEmpty()) {
            List<Long> ids = wikis.stream().map(Task::id).toList();
            runGroup(wikis, () -> knowledgeIndexer.indexWikiByIds(ids));
        }
        for (Task summary : summaries) {
            runGroup(List.of(summary),
                    () -> {
                        summaryVectorService.index(summary.sessionId(), summary.userId(), summary.text());
                        return 1;
                    });
        }
    }

    /**
     * 执行一组同类型任务，失败按任务 key 退避重试。
     *
     * @param tasks  这一组的任务（用于重试与日志）
     * @param action 实际动作
     */
    private void runGroup(List<Task> tasks, IntSupplier action) {
        try {
            int indexed = action.getAsInt();
            for (Task task : tasks) {
                attempts.remove(task.key());
            }
            log.info("[vector-index] op=index kind={} count={} indexed={}",
                    tasks.get(0).kind(), tasks.size(), indexed);
        } catch (Exception e) {
            Task sample = tasks.get(0);
            int failed = attempts.merge(sample.key(), 1, Integer::sum);
            if (failed >= MAX_ATTEMPTS) {
                attempts.remove(sample.key());
                log.warn("[vector-index] kind={} 连续失败 {} 次，放弃（数据留在库里等手动重建）：{}",
                        sample.kind(), failed, e.getMessage());
                return;
            }
            long delay = retryBaseMs * (1L << (failed - 1));
            log.warn("[vector-index] kind={} 索引失败，{} ms 后重试（第 {} 次）：{}",
                    sample.kind(), delay, failed, e.getMessage());
            for (Task task : tasks) {
                retryScheduler.schedule(() -> offer(task), delay, TimeUnit.MILLISECONDS);
            }
        }
    }
}
