package com.shiyi.domain.agent.adapter.repository;

import com.shiyi.domain.agent.model.entity.WikiEntryEntity;

import java.util.List;

/**
 * 知识库仓储（domain 视角）。
 * <p>
 * 只暴露"按关键词检索"和"写入一条"，不暴露 SQL 细节：
 * 换实现（比如以后接向量库）时，上层 {@code WikiService} 与工具定义都不用改。
 *
 * @author shiyi
 */
public interface IWikiRepository {

    /**
     * 关键词检索。
     *
     * @param userId   当前用户；空串表示只看全局条目
     * @param hostKey  当前主机；空串表示只看通用条目
     * @param keywords 检索词，中文按 ngram 切分
     * @param limit    最多返回条数
     * @return 按相关性降序的条目（含 score）
     */
    List<WikiEntryEntity> search(String userId, String hostKey, String keywords, int limit);

    /**
     * 按主键批量取（混合检索命中后回表取正文）。
     * <p>
     * 可见性在这里兜底：{@code (user_id = me OR user_id = '') AND (host_key = me OR host_key = '')}，
     * 与关键词检索同一口径。向量库里只有 id，越权过滤不能依赖它。
     *
     * @param ids     主键
     * @param userId  当前用户
     * @param hostKey 当前主机
     */
    List<WikiEntryEntity> queryByIds(List<Long> ids, String userId, String hostKey);

    /**
     * 新增或覆盖一条（按 title + hostKey + userId 唯一）。
     *
     * @return 主键；写入失败或参数非法返回 null
     */
    Long save(WikiEntryEntity entry);

    /**
     * 按主键批量取（不带可见性过滤，仅供向量索引使用）。
     * <p>
     * 索引侧要知道"这条到底还在不在、正文变成什么了"，不能用检索侧的可见性口径。
     *
     * @param ids 主键
     */
    List<WikiEntryEntity> findByIds(List<Long> ids);

    /**
     * 按 id 游标批量取用于向量索引的条目（含正文）。
     * <p>
     * 为什么用游标而不是分页：索引是全量扫描，游标翻页不会因为并发写入而漏行或重复；
     * 返回 id 升序，调用方把最后一条 id 传回来即可。
     *
     * @param afterId 只取 id 大于该值的条目；0 表示从头开始
     * @param limit   单批条数上限
     */
    List<WikiEntryEntity> listForIndex(long afterId, int limit);

    /** 命中计数 +1，供下次排序加权。 */
    void touchUsage(List<Long> ids);
}