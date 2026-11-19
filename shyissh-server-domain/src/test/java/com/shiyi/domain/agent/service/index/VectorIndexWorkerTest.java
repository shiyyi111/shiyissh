package com.shiyi.domain.agent.service.index;

import com.shiyi.domain.agent.adapter.repository.ILongTermMemoryRepository;
import com.shiyi.domain.agent.service.chat.SummaryVectorService;
import com.shiyi.domain.agent.service.knowledge.impl.KnowledgeIndexer;
import com.shiyi.domain.agent.service.memory.MemoryVectorService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VectorIndexWorker} 的单消费者行为验证：入队即处理、失败退避重试、启动排空。
 * <p>
 * 用真实的 worker 线程 + Mockito 桩，而不是把 process 拆出来测：
 * 这条链路的价值就在"生产者不等待、消费者阻塞等"，拆开测就测不到。
 */
public class VectorIndexWorkerTest {

    private MemoryVectorService memoryVectorService;
    private KnowledgeIndexer knowledgeIndexer;
    private SummaryVectorService summaryVectorService;
    private ILongTermMemoryRepository longTermMemoryRepository;
    private VectorIndexWorker worker;

    @Before
    public void setUp() {
        memoryVectorService = Mockito.mock(MemoryVectorService.class);
        knowledgeIndexer = Mockito.mock(KnowledgeIndexer.class);
        summaryVectorService = Mockito.mock(SummaryVectorService.class);
        longTermMemoryRepository = Mockito.mock(ILongTermMemoryRepository.class);

        worker = new VectorIndexWorker();
        worker.memoryVectorService = memoryVectorService;
        worker.knowledgeIndexer = knowledgeIndexer;
        worker.summaryVectorService = summaryVectorService;
        worker.longTermMemoryRepository = longTermMemoryRepository;
        worker.memoryIndexEnabled = true;
        // 测试里把启动等待与退避压到毫秒级，避免用例跑几秒
        worker.startupWaitMs = 0L;
        worker.retryBaseMs = 20L;
        worker.start();
    }

    @After
    public void tearDown() {
        worker.stop();
    }

    @Test
    public void shouldIndexEnqueuedMemoryWithoutWaitingForScan() throws Exception {
        when(memoryVectorService.indexByIds(anyList())).thenReturn(1);

        worker.enqueueMemory(42L);

        verify(memoryVectorService, timeout(2_000))
                .indexByIds(argThat(ids -> ids != null && ids.contains(42L)));
    }

    @Test
    public void shouldRetryAfterFailureInsteadOfDroppingTask() throws Exception {
        // 第一次抛异常、第二次成功：验证任务没有丢，而是按退避重试
        when(memoryVectorService.indexByIds(anyList()))
                .thenThrow(new IllegalStateException("qdrant down"))
                .thenReturn(1);

        worker.enqueueMemory(7L);

        verify(memoryVectorService, timeout(3_000).times(2))
                .indexByIds(argThat(ids -> ids != null && ids.contains(7L)));
    }

    @Test
    public void shouldGiveUpAfterMaxAttempts() throws Exception {
        when(memoryVectorService.indexByIds(anyList())).thenThrow(new IllegalStateException("qdrant down"));

        worker.enqueueMemory(9L);

        // 共 3 次尝试（首次 + 2 次重试）后放弃，不再无限重试
        verify(memoryVectorService, timeout(3_000).times(3)).indexByIds(anyList());
    }

    @Test
    public void shouldIndexEnqueuedWikiAndSummary() throws Exception {
        when(knowledgeIndexer.indexWikiByIds(anyList())).thenReturn(1);
        worker.enqueueWiki(11L);
        worker.enqueueSummary("s-1", "u-1", "摘要正文");

        verify(knowledgeIndexer, timeout(2_000))
                .indexWikiByIds(argThat(ids -> ids != null && ids.contains(11L)));
        verify(summaryVectorService, timeout(2_000)).index("s-1", "u-1", "摘要正文");
    }

    @Test
    public void shouldNotIndexMemoryWhenDisabled() throws Exception {
        worker.memoryIndexEnabled = false;

        worker.enqueueMemory(5L);

        CountDownLatch latch = new CountDownLatch(1);
        latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS);
        verify(memoryVectorService, never()).indexByIds(List.of(5L));
    }
}