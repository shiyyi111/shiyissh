package com.shiyi.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识库表 {@code agent_wiki} 的持久化对象。
 *
 * @author shiyi
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WikiEntryPO {

    private Long id;

    private String userId;

    private String hostKey;

    private String title;

    private String content;

    private String tags;

    private String source;

    private Integer hitCount;

    /** 相关性得分，仅检索结果有值（SQL 的 MATCH...AGAINST）。 */
    private Double score;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}