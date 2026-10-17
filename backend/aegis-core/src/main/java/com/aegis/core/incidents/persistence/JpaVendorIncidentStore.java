package com.aegis.core.incidents.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.aegis.core.incidents.VendorIncident;
import com.aegis.core.incidents.VendorIncidentStore;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

interface VendorIncidentJpaRepository extends JpaRepository<VendorIncidentEntity, UUID> {
    Optional<VendorIncidentEntity> findBySourceIdAndExternalId(String sourceId, String externalId);

    /** {@link VendorIncident#resolved()} in JPQL, and the same time the change-signals view gives an incident. */
    @Query("select i from VendorIncidentEntity i where i.resolvedAt is null"
            + " and (i.status is null or lower(i.status) not in ('resolved', 'completed'))"
            + " and coalesce(i.lastUpdateAt, i.startedAt, i.createdAt) >= :since")
    List<VendorIncidentEntity> findActive(@Param("since") Instant since);
}

/** JPA adapter for {@link VendorIncidentStore}. */
@Repository
class JpaVendorIncidentStore implements VendorIncidentStore {

    private final VendorIncidentJpaRepository incidents;

    JpaVendorIncidentStore(VendorIncidentJpaRepository incidents) {
        this.incidents = incidents;
    }

    @Override
    @Transactional
    public VendorIncident upsert(VendorIncident incident) {
        // Concurrency (scale-out): a concurrent insert of the same (source_id, external_id) loses the
        // unique constraint here; that surfaces to the drainer as a transient failure and is retried as
        // an update on the next run, so no extra locking is needed.
        VendorIncidentEntity entity = incidents
                .findBySourceIdAndExternalId(incident.sourceId(), incident.externalId())
                .orElseGet(() -> new VendorIncidentEntity(
                        UUID.randomUUID(), incident.sourceId(), incident.externalId()));

        entity.apply(
                incident.vendorKey(),
                incident.vendorName(),
                incident.title(),
                incident.status(),
                incident.impact(),
                incident.url(),
                incident.summary(),
                incident.startedAt(),
                incident.lastUpdateAt(),
                incident.resolvedAt());
        return toIncident(incidents.save(entity));
    }

    @Override
    public Optional<VendorIncident> findByExternalId(String sourceId, String externalId) {
        return incidents.findBySourceIdAndExternalId(sourceId, externalId).map(JpaVendorIncidentStore::toIncident);
    }

    @Override
    public Page<VendorIncident> findPage(Pageable pageable) {
        return incidents.findAll(pageable).map(JpaVendorIncidentStore::toIncident);
    }

    @Override
    public List<VendorIncident> findActive(Instant since) {
        return incidents.findActive(since).stream().map(JpaVendorIncidentStore::toIncident).toList();
    }

    private static VendorIncident toIncident(VendorIncidentEntity entity) {
        return new VendorIncident(
                entity.getId(),
                entity.getSourceId(),
                entity.getExternalId(),
                entity.getVendorKey(),
                entity.getVendorName(),
                entity.getTitle(),
                entity.getStatus(),
                entity.getImpact(),
                entity.getUrl(),
                entity.getSummary(),
                entity.getStartedAt(),
                entity.getLastUpdateAt(),
                entity.getResolvedAt());
    }
}
