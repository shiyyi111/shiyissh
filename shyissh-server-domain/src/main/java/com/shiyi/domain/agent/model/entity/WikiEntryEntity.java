package com.shiyi.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 运维知识库条目。
 * <p>
 * 归属维度有两个：{@code userId} 与 {@code hostKey}，空串表示"不限"。
 * 这样一条"nginx 配置在 /opt/nginx/conf"既能写给某台具体机器，也能写成所有机器通用的 SOP，
 * 检索时按 (本用户 OR 全局) × (本主机 OR 通用) 取并集。
 *
 * @author shiyi
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WikiEntryEntity {

    /** 主键。 */
    private Long id;

    /** 归属用户；空串=全局共享。 */
    private String userId;

    /** 归属主机（SSH 连接 ID）；空串=不限主机。 */
    private String hostKey;

    /** 标题，检索结果直接展示给模型/用户。 */
    private String title;

    /** 正文。 */
    private String content;

    /** 逗号分隔标签。 */
    private String tags;

    /** 来源：manual / agent / conversation。 */
    private String source;

    /** 被检索命中次数。 */
    private Integer hitCount;

    /** 相关性得分：只在检索结果里有值，由 SQL 的 MATCH...AGAINST 计算。 */
    private Double score;

    private Date createdAt;

    private Date updatedAt;
}