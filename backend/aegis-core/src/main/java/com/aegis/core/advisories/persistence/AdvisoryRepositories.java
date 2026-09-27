package com.aegis.core.advisories.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AdvisoryJpaRepository extends JpaRepository<AdvisoryEntity, UUID> {
    Optional<AdvisoryEntity> findBySourceIdAndExternalId(String sourceId, String externalId);

    List<AdvisoryEntity> findByCanonicalId(String canonicalId);

    List<AdvisoryEntity> findByCanonicalIdIn(Collection<String> canonicalIds);
}

interface AffectedProductJpaRepository extends JpaRepository<AffectedProductEntity, UUID> {
    List<AffectedProductEntity> findByAdvisoryIdIn(Collection<UUID> advisoryIds);

    /**
     * Advisories with an affected product whose normalised package name or CPE product is one of the
     * given keys. The two expressions are exactly the ones indexed in V18, so the lookup stays indexed.
     */
    @Query(value = """
            select distinct p.advisory_id
            from {h-schema}advisory_affected_products p
            where regexp_replace(lower(p.package_name), '[-_.]+', '-', 'g') in (:packageKeys)
               or btrim(regexp_replace(lower(p.product), '[^a-z0-9]+', '-', 'g'), '-') in (:productKeys)
            """, nativeQuery = true)
    List<UUID> findAdvisoryIdsNaming(
            @Param("packageKeys") Collection<String> packageKeys,
            @Param("productKeys") Collection<String> productKeys);

    /** A bulk delete: a derived {@code deleteBy} would load every row first only to delete it. */
    @Modifying
    @Query("delete from AffectedProductEntity a where a.advisoryId = :advisoryId")
    void deleteByAdvisoryId(@Param("advisoryId") UUID advisoryId);
}
