package com.aegis.core.ingestion.advisory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A normalised advisory — one feed entry (an NVD CVE today) reduced to the parts the pipeline
 * actually uses: the CVSS metric that scoring consumes and the affected (vendor, product,
 * version-range) tuples that matching resolves against registered components.
 *
 * <p>Feed-agnostic on purpose: {@code sourceId} distinguishes NVD from the OSV and vendor feeds that
 * land later, and {@code externalId} is the feed's own identifier (e.g. {@code CVE-2021-44228}).
 * An advisory is identified by that pair; a revised entry updates the same record.
 *
 * <p>{@code id} is null on a freshly parsed advisory — the store assigns it on first write.
 */
public record Advisory(
        UUID id,
        String sourceId,
        String externalId,
        String description,
        Instant publishedAt,
        Instant lastModifiedAt,
        Cvss cvss,
        List<AffectedProduct> affected) {

    public Advisory {
        affected = affected == null ? List.of() : List.copyOf(affected);
    }

    /**
     * The advisory's primary severity metric. NVD publishes several (CVSS v3.1, v3.0, v2, and
     * sometimes competing sources); the connector keeps one — the newest from the primary source —
     * because scoring needs a single comparable number. Null when the feed published none, which is
     * normal for a freshly disclosed CVE still awaiting analysis.
     */
    public record Cvss(String version, String vectorString, BigDecimal baseScore, String severity) {
    }
}
