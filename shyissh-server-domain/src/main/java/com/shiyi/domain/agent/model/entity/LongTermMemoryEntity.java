package com.shiyi.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 长期记忆实体（领域层）
 * <p>
 * 对应数据库 {@code long_term_memory} 表，存储从对话流中自动提取的结构化记忆。
 * 由 {@code LongTermMemoryService} 在 ReAct 循环各阶段自动提取并写入。
 * <p>
 * 四种记忆类型（{@code memoryType}）：
 * <ul>
 *   <li>{@code USER_PREFERENCE} - 用户偏好（含"以后""默认""记住"等偏好词），key=sha1("pref:"+内容)</li>
 *   <li>{@code ENVIRONMENT_FACT} - 环境事实（OS 检测），key="os:ubuntu"</li>
 *   <li>{@code SOFTWARE_FACT} - 软件版本（正则匹配），key="redis:version"</li>
 *   <li>{@code TROUBLESHOOTING_CASE} - 排查案例（失败信号/助手结论），key=sha1("failure:"/"assistant:"+内容)</li>
 * </ul>
 * <p>
 * 唯一键 (userId, scope, hostKey, memoryType, memoryKey) 保证同一记忆不重复入库，
 * 重复提取时 hit_count+1。scope=HOST 的记忆用 hostKey 区分不同主机——
 * "这台机器上 Redis 是 6.2"这类事实不应污染另一台机器。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LongTermMemoryEntity {

    /** 自增主键 */
    private Long id;

    /** 用户 ID */
    private String userId;

    /** 记忆作用域：USER（跨主机通用）/ HOST（绑定某台主机）/ SESSION（仅本会话） */
    private String scope;

    /** 主机标识，scope=HOST 时有效；其它作用域为空串 */
    private String hostKey;

    /** 来源会话 ID */
    private String sessionId;

    /** 记忆类型：USER_PREFERENCE / ENVIRONMENT_FACT / SOFTWARE_FACT / TROUBLESHOOTING_CASE */
    private String memoryType;

    /** 记忆去重键（不同类型有不同生成策略，保证幂等） */
    private String memoryKey;

    /** 记忆内容（已截断，最长 300~500 字符） */
    private String content;

    /** 关键词集合（逗号分隔，用于召回时关键词重叠匹配） */
    private String keywords;

    /** 来源角色：user / tool / assistant */
    private String sourceRole;

    /** 置信度（0.0~1.0，USER_PREFERENCE=0.85 最高） */
    private Double confidence;

    /** 重要度（0.0~1.0），参与召回打分 */
    private Double importance;

    /** 命中/更新次数（同一记忆被重复提取时累加） */
    private Integer hitCount;

    /** 最近一次被召回的时间（参与时间衰减打分） */
    private Date lastUsedAt;

    /** 过期时间，NULL 表示不过期 */
    private Date expiresAt;

    /** 创建时间 */
    private Date createdAt;

    /** 更新时间 */
    private Date updatedAt;

    /**
     * 向量索引状态：{@code 0} 待索引 / {@code 1} 已索引 / {@code 2} 索引失败。
     * <p>
     * 记忆召回走 Qdrant 语义候选，所以新写入或内容被更新的记忆必须回到 0 等后台任务重新索引；
     * 失败达 {@link #embeddingAttempts} 上限后不再重试，避免坏数据反复打外部接口。
     */
    private Integer embeddingStatus;

    /** 索引尝试次数（上限 3） */
    private Integer embeddingAttempts;

    /** 最近一次索引成功时间 */
    private Date embeddingUpdatedAt;
}
