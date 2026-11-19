package com.shiyi.domain.agent.service.wiki;

import com.shiyi.domain.agent.adapter.repository.IWikiRepository;
import com.shiyi.domain.agent.model.entity.WikiEntryEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 运维知识库服务：把"人写的经验"和"Agent 沉淀的结论"变成模型可检索的资料。
 * <p>
 * 为什么要有这一层而不是把表直接扔给模型：
 * <ul>
 *   <li><b>检索结果是"资料"而不是"事实"</b>：模型必须能区分"我查到的条目说 X"和"我亲眼看到 X"。
 *       所以每条结果都带编号与标题，模型回答时可以引用（如"按知识库 [2]"），用户能回溯是哪条经验；</li>
 *   <li><b>截断要在这里做</b>：一条 wiki 可能有几千字，直接回填会让上下文瞬间爆掉；
 *       这里统一截到 {@link #SNIPPET_CHARS} 字并折叠空白，保证检索结果本身可控；</li>
 *   <li><b>空结果要显式说"没找到"</b>：返回空串会让模型以为工具坏了从而编造答案。</li>
 * </ul>
 *
 * @author shiyi
 */
@Service
public class WikiService {

    private static final Logger log = LoggerFactory.getLogger(WikiService.class);

    /** 单条结果回填给模型的正文上限；够模型判断"这条是不是我要的"。 */
    private static final int SNIPPET_CHARS = 300;

    /** 默认返回条数。 */
    public static final int DEFAULT_LIMIT = 5;

    @Resource
    private IWikiRepository wikiRepository;

    @Resource
    private com.shiyi.domain.agent.service.index.VectorIndexWorker vectorIndexWorker;

    /**
     * 检索知识库。
     *
     * @param userId  当前用户
     * @param hostKey 当前主机（空串表示只看通用条目）
     * @param query   检索词（中文按 2 字滑窗匹配）
     * @param limit   条数上限
     */
    public List<WikiHit> search(String userId, String hostKey, String query, int limit) {
        if (wikiRepository == null || query == null || query.isBlank()) {
            return List.of();
        }
        int effectiveLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 10);
        List<WikiEntryEntity> entries = wikiRepository.search(userId, hostKey, query, effectiveLimit);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<WikiHit> hits = new ArrayList<>(entries.size());
        List<Long> hitIds = new ArrayList<>(entries.size());
        for (WikiEntryEntity entry : entries) {
            hits.add(new WikiHit(entry.getId(), entry.getTitle(), snippet(entry.getContent()),
                    entry.getTags(), entry.getScore() == null ? 0d : entry.getScore()));
            if (entry.getId() != null) {
                hitIds.add(entry.getId());
            }
        }
        // 命中计数只影响下次排序，失败不能影响本次回答
        try {
            wikiRepository.touchUsage(hitIds);
        } catch (Exception e) {
            log.warn("[wiki] 命中计数更新失败: {}", e.getMessage());
        }
        return hits;
    }

    /**
     * 把检索结果渲染成工具返回值。
     * <p>
     * 编号从 1 开始，模型引用时写成 [1]、[2]；没有结果时给一句明确的话。
     */
    public String render(List<WikiHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "（知识库里没有找到相关条目。请把这次结论当作新知识，排查完成后用 saveWiki 沉淀。）";
        }
        StringBuilder text = new StringBuilder("知识库检索结果（引用时请写 [编号]，这些是历史经验，不是当前机器的实时状态）：");
        for (int i = 0; i < hits.size(); i++) {
            WikiHit hit = hits.get(i);
            text.append("\n[").append(i + 1).append("] ").append(hit.title());
            if (hit.tags() != null && !hit.tags().isBlank()) {
                text.append("（标签：").append(hit.tags()).append("）");
            }
            text.append("\n    ").append(hit.snippet());
        }
        return text.toString();
    }

    /**
     * 写入一条知识（人工或 Agent 沉淀）。
     *
     * @return 是否写入成功
     */
    public boolean remember(String userId, String hostKey, String title, String content, String tags) {
        if (wikiRepository == null || title == null || title.isBlank()
                || content == null || content.isBlank()) {
            return false;
        }
        try {
            Long wikiId = wikiRepository.save(WikiEntryEntity.builder()
                    .userId(userId == null ? "" : userId)
                    .hostKey(hostKey == null ? "" : hostKey)
                    .title(title.strip())
                    .content(content.strip())
                    .tags(tags == null ? "" : tags.strip())
                    .source("agent")
                    .build());
            if (wikiId != null && vectorIndexWorker != null) {
                // 交由后台单消费者线程做向量化，写入路径不等待 embedding
                vectorIndexWorker.enqueueWiki(wikiId);
            }
            return true;
        } catch (Exception e) {
            log.warn("[wiki] 写入失败 title={}: {}", title, e.getMessage());
            return false;
        }
    }

    /** 正文截断：换行折成空格，避免一条多行记录在提示里占掉十几行。 */
    private String snippet(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String text = content.strip().replaceAll("\\s+", " ");
        return text.length() <= SNIPPET_CHARS ? text : text.substring(0, SNIPPET_CHARS) + "…";
    }

    /**
     * 一条检索命中。
     *
     * @param id      条目主键（模型不需要，但日志/前端引用时要）
     * @param title   标题
     * @param snippet 截断后的正文
     * @param tags    标签
     * @param score   相关性得分
     */
    public record WikiHit(long id, String title, String snippet, String tags, double score) {
    }
}