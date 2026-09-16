package com.aegis.core.ingestion.advisory;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Persistence port for normalised advisories. */
public interface AdvisoryStore {

    /**
     * Insert the advisory, or update the existing one with the same {@code (sourceId, externalId)}.
     * Affected products are replaced wholesale — a revised advisory may drop or re-scope ranges, so
     * merging them row by row would leave stale tuples behind.
     */
    Advisory upsert(Advisory advisory);

    Optional<Advisory> findByExternalId(String sourceId, String externalId);

    /** A page of advisories, newest revision first, each with its affected products. */
    Page<Advisory> findPage(Pageable pageable);
}
