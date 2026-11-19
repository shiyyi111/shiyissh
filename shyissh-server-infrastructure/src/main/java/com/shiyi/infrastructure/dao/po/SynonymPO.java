package com.shiyi.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 同义词 PO（{@code ops_synonym}）。
 *
 * @author shiyi
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SynonymPO {

    private Long id;

    private String alias;

    private String canonical;

    private String lang;

    private Double weight;

    private Integer enabled;

    private String remark;
}