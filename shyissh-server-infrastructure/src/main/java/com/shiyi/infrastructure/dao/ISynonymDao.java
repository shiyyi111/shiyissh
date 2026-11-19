package com.shiyi.infrastructure.dao;

import com.shiyi.infrastructure.dao.po.SynonymPO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 同义词 DAO（{@code ops_synonym}）。
 *
 * @author shiyi
 */
@Mapper
public interface ISynonymDao {

    /** 全部启用条目；调用方整表缓存。 */
    List<SynonymPO> queryEnabled();
}