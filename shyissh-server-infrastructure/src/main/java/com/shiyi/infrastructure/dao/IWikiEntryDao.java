package com.shiyi.infrastructure.dao;

import com.shiyi.infrastructure.dao.po.WikiEntryPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识库 DAO。
 *
 * @author shiyi
 */
@Mapper
public interface IWikiEntryDao {

    /**
     * FULLTEXT ngram 检索。
     *
     * @param userId   归属用户（含全局条目）
     * @param hostKey  归属主机（含通用条目）
     * @param keywords 检索关键词
     * @param limit    条数上限
     */
    List<WikiEntryPO> fulltextSearch(@Param("userId") String userId,
                                     @Param("hostKey") String hostKey,
                                     @Param("keywords") String keywords,
                                     @Param("limit") int limit);

    /** 按主键批量取（含归属过滤），供混合检索回表。 */
    List<WikiEntryPO> queryByIds(@Param("ids") List<Long> ids,
                                 @Param("userId") String userId,
                                 @Param("hostKey") String hostKey);

    /** 按主键批量取（不带可见性过滤），供写入路径的即时向量索引用。 */
    List<WikiEntryPO> listByIds(@Param("ids") List<Long> ids);

    /** 按 (userId, hostKey, title) 精确查，供 save 做覆盖判断。 */
    WikiEntryPO queryByIdentity(@Param("userId") String userId,
                                @Param("hostKey") String hostKey,
                                @Param("title") String title);

    int insert(WikiEntryPO entry);

    int update(WikiEntryPO entry);

    int touchUsage(@Param("ids") List<Long> ids);
    /**
     * 按 id 游标取全量条目（含正文），供向量索引全量回填。
     *
     * @param afterId 只取 id 大于该值
     * @param limit   单批条数
     */
    List<WikiEntryPO> listForIndex(@Param("afterId") long afterId, @Param("limit") int limit);

}