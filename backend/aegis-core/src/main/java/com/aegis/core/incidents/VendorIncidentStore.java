package com.aegis.core.incidents;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Persistence port for normalised vendor incidents. */
public interface VendorIncidentStore {

    /**
     * Insert the incident, or update the existing one with the same {@code (sourceId, externalId)}.
     * Vendors edit incidents in place as they investigate, so a re-poll updates rather than duplicates.
     */
    VendorIncident upsert(VendorIncident incident);

    Optional<VendorIncident> findByExternalId(String sourceId, String externalId);

    /** A page of incidents, most recently started first. */
    Page<VendorIncident> findPage(Pageable pageable);
}
