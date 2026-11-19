package com.shiyi.domain.agent.adapter.repository;

import com.shiyi.domain.agent.model.entity.SynonymEntity;

import java.util.List;

/**
 * 同义词/别名仓储（{@code ops_synonym}）。
 * <p>
 * 只读：这张表由人工维护（或导入脚本），运行期只加载不修改。
 *
 * @author shiyi
 */
public interface ISynonymRepository {

    /**
     * 加载全部启用的别名。
     * <p>
     * 条目很少（几十到几百条），调用方可以整表缓存，不必做增量。
     */
    List<SynonymEntity> queryEnabled();
}