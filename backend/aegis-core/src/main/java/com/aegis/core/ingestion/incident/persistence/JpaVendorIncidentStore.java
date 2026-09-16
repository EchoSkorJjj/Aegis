package com.aegis.core.ingestion.incident.persistence;

import java.util.Optional;
import java.util.UUID;

import com.aegis.core.ingestion.incident.VendorIncident;
import com.aegis.core.ingestion.incident.VendorIncidentStore;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

interface VendorIncidentJpaRepository extends JpaRepository<VendorIncidentEntity, UUID> {
    Optional<VendorIncidentEntity> findBySourceIdAndExternalId(String sourceId, String externalId);
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
