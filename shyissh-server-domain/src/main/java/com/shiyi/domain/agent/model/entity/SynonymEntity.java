package com.shiyi.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 同义词映射（{@code ops_synonym} 一行）。
 * <p>
 * 例：{@code alias="磁盘满"} → {@code canonical="磁盘空间不足 No space left on device"}。
 * 归一化时把别名替换成标准词，让"口语说法"也能命中"日志原文"。
 *
 * @author shiyi
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SynonymEntity {

    private Long id;

    /** 用户可能说的词。 */
    private String alias;

    /** 归一化后的标准检索词。 */
    private String canonical;

    /** zh / en。 */
    private String lang;

    /** 一个别名有多条映射时的排序权重。 */
    private Double weight;

    private Integer enabled;

    private String remark;
}