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
}

interface AffectedProductJpaRepository extends JpaRepository<AffectedProductEntity, UUID> {
    List<AffectedProductEntity> findByAdvisoryIdIn(Collection<UUID> advisoryIds);

    /** A bulk delete: a derived {@code deleteBy} would load every row first only to delete it. */
    @Modifying
    @Query("delete from AffectedProductEntity a where a.advisoryId = :advisoryId")
    void deleteByAdvisoryId(@Param("advisoryId") UUID advisoryId);
}
