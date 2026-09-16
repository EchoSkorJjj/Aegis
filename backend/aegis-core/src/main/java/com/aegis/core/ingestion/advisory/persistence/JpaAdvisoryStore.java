package com.aegis.core.ingestion.advisory.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.aegis.core.ingestion.advisory.Advisory;
import com.aegis.core.ingestion.advisory.AdvisoryStore;
import com.aegis.core.ingestion.advisory.AffectedProduct;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter for {@link AdvisoryStore}. */
@Repository
class JpaAdvisoryStore implements AdvisoryStore {

    private final AdvisoryJpaRepository advisories;
    private final AffectedProductJpaRepository affectedProducts;

    JpaAdvisoryStore(AdvisoryJpaRepository advisories, AffectedProductJpaRepository affectedProducts) {
        this.advisories = advisories;
        this.affectedProducts = affectedProducts;
    }

    @Override
    @Transactional
    public Advisory upsert(Advisory advisory) {
        // Concurrency (scale-out): if another instance inserts this (source_id, external_id) first, the
        // insert here loses the unique constraint and surfaces to the drainer as a transient failure —
        // retried as an update on the next run — so no extra locking is needed.
        AdvisoryEntity entity = advisories
                .findBySourceIdAndExternalId(advisory.sourceId(), advisory.externalId())
                .orElseGet(() -> new AdvisoryEntity(
                        UUID.randomUUID(), advisory.sourceId(), advisory.externalId()));

        Advisory.Cvss cvss = advisory.cvss();
        entity.apply(
                advisory.description(),
                advisory.publishedAt(),
                advisory.lastModifiedAt(),
                cvss == null ? null : cvss.version(),
                cvss == null ? null : cvss.vectorString(),
                cvss == null ? null : cvss.baseScore(),
                cvss == null ? null : cvss.severity());
        AdvisoryEntity saved = advisories.save(entity);

        // Replace wholesale: a revision may drop or re-scope ranges, so merging would strand stale rows.
        affectedProducts.deleteByAdvisoryId(saved.getId());
        List<AffectedProductEntity> rows = advisory.affected().stream()
                .map(affected -> toEntity(saved.getId(), affected))
                .toList();
        affectedProducts.saveAll(rows);

        return toAdvisory(saved, advisory.affected());
    }

    @Override
    public Optional<Advisory> findByExternalId(String sourceId, String externalId) {
        return advisories.findBySourceIdAndExternalId(sourceId, externalId)
                .map(entity -> toAdvisory(entity, affectedFor(List.of(entity.getId())).get(entity.getId())));
    }

    @Override
    public Page<Advisory> findPage(Pageable pageable) {
        Page<AdvisoryEntity> page = advisories.findAll(pageable);
        Map<UUID, List<AffectedProduct>> affected = affectedFor(
                page.getContent().stream().map(AdvisoryEntity::getId).toList());
        return page.map(entity -> toAdvisory(entity, affected.get(entity.getId())));
    }

    /** One query for the whole page's affected products, so listing never degrades into an N+1. */
    private Map<UUID, List<AffectedProduct>> affectedFor(Collection<UUID> advisoryIds) {
        if (advisoryIds.isEmpty()) {
            return Map.of();
        }
        return affectedProducts.findByAdvisoryIdIn(advisoryIds).stream()
                .collect(Collectors.groupingBy(
                        AffectedProductEntity::getAdvisoryId,
                        Collectors.mapping(JpaAdvisoryStore::toAffectedProduct, Collectors.toList())));
    }

    private static Advisory toAdvisory(AdvisoryEntity entity, List<AffectedProduct> affected) {
        Advisory.Cvss cvss = entity.getCvssVersion() == null && entity.getCvssBaseScore() == null
                ? null
                : new Advisory.Cvss(
                        entity.getCvssVersion(),
                        entity.getCvssVector(),
                        entity.getCvssBaseScore(),
                        entity.getCvssSeverity());
        return new Advisory(
                entity.getId(),
                entity.getSourceId(),
                entity.getExternalId(),
                entity.getDescription(),
                entity.getPublishedAt(),
                entity.getLastModifiedAt(),
                cvss,
                affected == null ? List.of() : new ArrayList<>(affected));
    }

    private static AffectedProduct toAffectedProduct(AffectedProductEntity entity) {
        return new AffectedProduct(
                entity.getCpe(),
                entity.getPart(),
                entity.getVendor(),
                entity.getProduct(),
                entity.getVersion(),
                entity.getVersionStartIncluding(),
                entity.getVersionStartExcluding(),
                entity.getVersionEndIncluding(),
                entity.getVersionEndExcluding());
    }

    private static AffectedProductEntity toEntity(UUID advisoryId, AffectedProduct affected) {
        return new AffectedProductEntity(
                UUID.randomUUID(),
                advisoryId,
                affected.cpe(),
                affected.part(),
                affected.vendor(),
                affected.product(),
                affected.version(),
                affected.versionStartIncluding(),
                affected.versionStartExcluding(),
                affected.versionEndIncluding(),
                affected.versionEndExcluding());
    }
}
