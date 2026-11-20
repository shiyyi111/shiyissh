package com.shiyi.domain.agent.service.knowledge.impl;

import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 静态知识同步：启动时把 {@code docs/knowledge-base/*.md} 切分写入 Qdrant，并提供手动重同步入口。
 * <p>
 * <b>为什么改成"启动同步 + 手动重同步"，而不是定时扫</b>：md 是随应用发布的静态文件，
 * 进程活着的时候它不会变（改了要重新打包/重启），定时扫描只会每秒空转一次文件系统；
 * 真正需要增量的场景（本地改 md 调试）手动调 {@link #sync()} 就够了。
 * <p>
 * <b>为什么在独立线程里跑</b>：启动同步要发几十次 embedding 请求（70 条约 7 批，250ms/批），
 * 放主线程会拖慢端口就绪；失败也不该影响应用启动——索引可以重建，服务不能起不来。
 * <p>
 * 配置：{@code app.rag.knowledge.dir}（默认 {@code docs/knowledge-base}）、
 * {@code app.rag.knowledge.sync-on-startup}（默认 true）。
 *
 * @author shiyi
 */
@Service
public class KnowledgeSourceSync {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSourceSync.class);

    @Value("${app.rag.knowledge.dir:docs/knowledge-base}")
    private String knowledgeDir;

    @Value("${app.rag.knowledge.sync-on-startup:true}")
    private boolean syncOnStartup;

    @Resource
    private KnowledgeIndexer knowledgeIndexer;

    /** 关键词腿索引（Lucene/BM25）：md 由索引器重建，wiki 在这里补扫。 */
    @Resource
    private Bm25KnowledgeIndex bm25Index;

    /**
     * 启动同步：异步执行，异常只记日志。
     */
    @EventListener(ContextRefreshedEvent.class)
    public void syncOnStartup() {
        if (!syncOnStartup) {
            log.info("[knowledge] app.rag.knowledge.sync-on-startup=false，跳过启动同步");
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                sync();
            } catch (Exception e) {
                log.error("[knowledge] 启动同步 md 静态知识失败：{}", e.getMessage(), e);
            }
        }, "knowledge-source-sync");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 手动同步一次（也可被管理接口调用）。
     *
     * @return 同步统计
     */
    public KnowledgeIndexer.SyncStats sync() {
        Path dir = resolveKnowledgeDir();
        log.info("[knowledge] 开始同步 md 静态知识：{}", dir.toAbsolutePath());
        KnowledgeIndexer.SyncStats stats = knowledgeIndexer.syncMarkdown(dir);
        // wiki 在 MySQL 里、不在 md 里：进程重启后内存倒排索引是空的，必须补扫一次，
        // 否则"重启后 wiki 关键词搜不到"会变成只在重启后复现的怪问题
        try {
            bm25Index.rebuildWiki();
        } catch (Exception e) {
            log.warn("[knowledge] wiki 关键词索引补扫失败（md 关键词召回不受影响）：{}", e.getMessage());
        }
        return stats;
    }

    /** 供读取配置的测试用（包内可见，避免反射）。 */
    Path resolveDir() {
        return Path.of(knowledgeDir);
    }

    /**
     * 解析 md 知识目录。
     * <p>
     * 默认配置是相对路径 {@code docs/knowledge-base}，相对的是<b>进程工作目录</b>：
     * 从仓库根启动能命中，从模块目录启动（{@code cd shyissh-server-app && mvn spring-boot:run}）
     * 会解析到 {@code shyissh-server-app/docs/knowledge-base}，那里没有文件，结果就是"零条知识"
     * 却只留一条 warn，看起来非常像 RAG 坏了。这里在直接路径不存在时向上找两级，
     * 命中就用，并把实际路径写进日志。
     */
    private Path resolveKnowledgeDir() {
        Path configured = Path.of(knowledgeDir);
        if (Files.isDirectory(configured)) {
            return configured;
        }
        Path probe = configured;
        for (int i = 0; i < 3 && probe.getParent() != null; i++) {
            probe = Path.of("..").resolve(probe).normalize();
            if (Files.isDirectory(probe)) {
                log.info("[knowledge] 配置目录 {} 不存在，改用 {}（按工作目录向上找）", configured, probe);
                return probe;
            }
        }
        return configured;
    }
}
