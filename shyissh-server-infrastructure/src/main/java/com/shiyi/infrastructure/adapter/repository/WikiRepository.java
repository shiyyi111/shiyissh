package com.shiyi.infrastructure.adapter.repository;

import com.shiyi.domain.agent.adapter.repository.IWikiRepository;
import com.shiyi.domain.agent.model.entity.WikiEntryEntity;
import com.shiyi.infrastructure.dao.IWikiEntryDao;
import com.shiyi.infrastructure.dao.po.WikiEntryPO;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 知识库仓储实现：PO ↔ Entity 转换 + 空值兜底。
 *
 * @author shiyi
 */
@Repository
public class WikiRepository implements IWikiRepository {

    private static final String SOURCE_MANUAL = "manual";

    @Resource
    private IWikiEntryDao wikiEntryDao;

    @Override
    public List<WikiEntryEntity> search(String userId, String hostKey, String keywords, int limit) {
        if (wikiEntryDao == null || isBlank(keywords)) {
            return List.of();
        }
        List<WikiEntryPO> rows = wikiEntryDao.fulltextSearch(nullSafe(userId), nullSafe(hostKey),
                keywords.strip(), limit <= 0 ? 5 : limit);
        List<WikiEntryEntity> result = new ArrayList<>(rows.size());
        for (WikiEntryPO row : rows) {
            result.add(toEntity(row));
        }
        return result;
    }

    @Override
    public List<WikiEntryEntity> listForIndex(long afterId, int limit) {
        if (wikiEntryDao == null) {
            return List.of();
        }
        List<WikiEntryPO> rows = wikiEntryDao.listForIndex(afterId, limit <= 0 ? 200 : limit);
        List<WikiEntryEntity> result = new ArrayList<>(rows.size());
        for (WikiEntryPO row : rows) {
            result.add(toEntity(row));
        }
        return result;
    }

    @Override
    public List<WikiEntryEntity> queryByIds(List<Long> ids, String userId, String hostKey) {
        if (wikiEntryDao == null || ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<WikiEntryPO> rows = wikiEntryDao.queryByIds(ids, nullSafe(userId), nullSafe(hostKey));
        List<WikiEntryEntity> result = new ArrayList<>(rows.size());
        for (WikiEntryPO row : rows) {
            result.add(toEntity(row));
        }
        return result;
    }

    @Override
    public Long save(WikiEntryEntity entry) {
        if (wikiEntryDao == null || entry == null || isBlank(entry.getTitle())) {
            return null;
        }
        WikiEntryPO existing = wikiEntryDao.queryByIdentity(nullSafe(entry.getUserId()),
                nullSafe(entry.getHostKey()), entry.getTitle().strip());
        if (existing == null) {
            WikiEntryPO created = WikiEntryPO.builder()
                    .userId(nullSafe(entry.getUserId()))
                    .hostKey(nullSafe(entry.getHostKey()))
                    .title(entry.getTitle().strip())
                    .content(nullSafe(entry.getContent()))
                    .tags(nullSafe(entry.getTags()))
                    .source(isBlank(entry.getSource()) ? SOURCE_MANUAL : entry.getSource())
                    .hitCount(0)
                    .build();
            wikiEntryDao.insert(created);
            return created.getId();
        }
        wikiEntryDao.update(WikiEntryPO.builder()
                .id(existing.getId())
                .content(nullSafe(entry.getContent()))
                .tags(nullSafe(entry.getTags()))
                .source(isBlank(entry.getSource()) ? existing.getSource() : entry.getSource())
                .build());
        return existing.getId();
    }

    @Override
    public List<WikiEntryEntity> findByIds(List<Long> ids) {
        if (wikiEntryDao == null || ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<WikiEntryPO> rows = wikiEntryDao.listByIds(ids);
        List<WikiEntryEntity> result = new ArrayList<>(rows.size());
        for (WikiEntryPO row : rows) {
            result.add(toEntity(row));
        }
        return result;
    }

    @Override
    public void touchUsage(List<Long> ids) {
        if (wikiEntryDao == null || ids == null || ids.isEmpty()) {
            return;
        }
        wikiEntryDao.touchUsage(ids);
    }

    private WikiEntryEntity toEntity(WikiEntryPO po) {
        return WikiEntryEntity.builder()
                .id(po.getId())
                .userId(po.getUserId())
                .hostKey(po.getHostKey())
                .title(po.getTitle())
                .content(po.getContent())
                .tags(po.getTags())
                .source(po.getSource())
                .hitCount(po.getHitCount())
                .score(po.getScore())
                .createdAt(po.getCreatedAt() == null ? null : Date.from(
                        po.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant()))
                .updatedAt(po.getUpdatedAt() == null ? null : Date.from(
                        po.getUpdatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant()))
                .build();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}