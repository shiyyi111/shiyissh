package com.shiyi.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 长期记忆持久化对象（PO）
 * <p>
 * 对应数据库 {@code long_term_memory} 表，存储从对话流中自动提取的结构化记忆。
 * <p>
 * 唯一键 (userId, scope, hostKey, memoryType, memoryKey) 保证同一记忆不重复入库，
 * 重复提取时走 UPDATE 并 hit_count+1。scope=HOST 的记忆用 hostKey 区分不同主机，
 * 避免同一用户在多台机器上的同名记忆互相覆盖。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LongTermMemoryPO {

    /** 自增主键 */
    private Long id;

    /** 用户 ID */
    private String userId;

    /** 记忆作用域：USER（跨主机通用）/ HOST（绑定某台主机）/ SESSION（仅本会话） */
    private String scope;

    /** 主机标识，scope=HOST 时有效；其它作用域为空串（唯一索引要求非空） */
    private String hostKey;

    /** 来源会话 ID */
    private String sessionId;

    /** 记忆类型：USER_PREFERENCE / ENVIRONMENT_FACT / SOFTWARE_FACT / TROUBLESHOOTING_CASE */
    private String memoryType;

    /** 记忆去重键（不同类型有不同生成策略） */
    private String memoryKey;

    /** 记忆内容 */
    private String content;

    /** 关键词集合（逗号分隔，用于召回匹配） */
    private String keywords;

    /** 来源角色：user / tool / assistant */
    private String sourceRole;

    /** 置信度（0.0~1.0） */
    private Double confidence;

    /** 重要度（0.0~1.0），参与召回打分，越高越容易被召回 */
    private Double importance;

    /** 命中/更新次数 */
    private Integer hitCount;

    /** 最近一次被召回的时间，参与衰减打分 */
    private LocalDateTime lastUsedAt;

    /** 过期时间，NULL 表示不过期；召回时过滤已过期的记忆 */
    private LocalDateTime expiresAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 向量索引状态：0 待索引 / 1 已索引 / 2 索引失败（达到重试上限后不再尝试） */
    private Integer embeddingStatus;

    /** 索引尝试次数，达到 {@code 3} 后不再重试，避免坏数据反复打外部模型接口 */
    private Integer embeddingAttempts;

    /** 最近一次索引成功时间，用于排查"记忆没被召回是不是还没进索引" */
    private LocalDateTime embeddingUpdatedAt;
}
