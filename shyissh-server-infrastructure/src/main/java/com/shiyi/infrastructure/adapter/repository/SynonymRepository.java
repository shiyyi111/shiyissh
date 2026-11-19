package com.shiyi.infrastructure.adapter.repository;

import com.shiyi.domain.agent.adapter.repository.ISynonymRepository;
import com.shiyi.domain.agent.model.entity.SynonymEntity;
import com.shiyi.infrastructure.dao.ISynonymDao;
import com.shiyi.infrastructure.dao.po.SynonymPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 同义词仓储实现。
 *
 * @author shiyi
 */
@Repository
public class SynonymRepository implements ISynonymRepository {

    @Resource
    private ISynonymDao synonymDao;

    @Override
    public List<SynonymEntity> queryEnabled() {
        if (synonymDao == null) {
            return List.of();
        }
        List<SynonymPO> rows = synonymDao.queryEnabled();
        List<SynonymEntity> result = new ArrayList<>(rows.size());
        for (SynonymPO row : rows) {
            result.add(SynonymEntity.builder()
                    .id(row.getId())
                    .alias(row.getAlias())
                    .canonical(row.getCanonical())
                    .lang(row.getLang())
                    .weight(row.getWeight())
                    .enabled(row.getEnabled())
                    .remark(row.getRemark())
                    .build());
        }
        return result;
    }
}