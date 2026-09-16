package com.aegis.core.ingestion.news.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.aegis.core.ingestion.news.NewsItem;
import com.aegis.core.ingestion.news.NewsItemStore;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

interface NewsItemJpaRepository extends JpaRepository<NewsItemEntity, UUID> {
    Optional<NewsItemEntity> findBySourceIdAndExternalId(String sourceId, String externalId);

    @Query("select i from NewsItemEntity i where i.id in "
            + "(select r.newsItemId from NewsItemCveReferenceEntity r where upper(r.cveId) = upper(:cveId))")
    Page<NewsItemEntity> findByCveReference(@Param("cveId") String cveId, Pageable pageable);
}

interface NewsItemCveReferenceJpaRepository extends JpaRepository<NewsItemCveReferenceEntity, UUID> {
    List<NewsItemCveReferenceEntity> findByNewsItemIdIn(Collection<UUID> newsItemIds);

    /** A bulk delete: a derived {@code deleteBy} would load every row first only to delete it. */
    @Modifying
    @Query("delete from NewsItemCveReferenceEntity r where r.newsItemId = :newsItemId")
    void deleteByNewsItemId(@Param("newsItemId") UUID newsItemId);
}

/** JPA adapter for {@link NewsItemStore}. */
@Repository
class JpaNewsItemStore implements NewsItemStore {

    private final NewsItemJpaRepository items;
    private final NewsItemCveReferenceJpaRepository references;

    JpaNewsItemStore(NewsItemJpaRepository items, NewsItemCveReferenceJpaRepository references) {
        this.items = items;
        this.references = references;
    }

    @Override
    @Transactional
    public NewsItem upsert(NewsItem item) {
        // Concurrency (scale-out): a concurrent insert of the same (source_id, external_id) loses the
        // unique constraint here; that surfaces to the drainer as a transient failure and is retried as
        // an update on the next run, so no extra locking is needed.
        NewsItemEntity entity = items
                .findBySourceIdAndExternalId(item.sourceId(), item.externalId())
                .orElseGet(() -> new NewsItemEntity(UUID.randomUUID(), item.sourceId(), item.externalId()));

        entity.apply(
                item.feedKey(),
                item.feedName(),
                item.title(),
                item.url(),
                item.summary(),
                item.publishedAt());
        NewsItemEntity saved = items.save(entity);

        // Replace wholesale: an edited article may no longer mention a CVE it once did.
        references.deleteByNewsItemId(saved.getId());
        references.saveAll(item.cveReferences().stream()
                .map(cveId -> new NewsItemCveReferenceEntity(UUID.randomUUID(), saved.getId(), cveId))
                .toList());

        return toItem(saved, item.cveReferences());
    }

    @Override
    public Optional<NewsItem> findByExternalId(String sourceId, String externalId) {
        return items.findBySourceIdAndExternalId(sourceId, externalId)
                .map(entity -> toItem(entity, referencesFor(List.of(entity.getId())).get(entity.getId())));
    }

    @Override
    public Page<NewsItem> findPage(Pageable pageable) {
        return withReferences(items.findAll(pageable));
    }

    @Override
    public Page<NewsItem> findPageByCveReference(String cveId, Pageable pageable) {
        return withReferences(items.findByCveReference(cveId, pageable));
    }

    private Page<NewsItem> withReferences(Page<NewsItemEntity> page) {
        Map<UUID, List<String>> references = referencesFor(
                page.getContent().stream().map(NewsItemEntity::getId).toList());
        return page.map(entity -> toItem(entity, references.get(entity.getId())));
    }

    /** One query for the whole page's references, so listing never degrades into an N+1. */
    private Map<UUID, List<String>> referencesFor(Collection<UUID> newsItemIds) {
        if (newsItemIds.isEmpty()) {
            return Map.of();
        }
        return references.findByNewsItemIdIn(newsItemIds).stream()
                .collect(Collectors.groupingBy(
                        NewsItemCveReferenceEntity::getNewsItemId,
                        Collectors.mapping(NewsItemCveReferenceEntity::getCveId, Collectors.toList())));
    }

    private static NewsItem toItem(NewsItemEntity entity, List<String> cveReferences) {
        return new NewsItem(
                entity.getId(),
                entity.getSourceId(),
                entity.getExternalId(),
                entity.getFeedKey(),
                entity.getFeedName(),
                entity.getTitle(),
                entity.getUrl(),
                entity.getSummary(),
                entity.getPublishedAt(),
                cveReferences == null ? List.of() : cveReferences);
    }
}
