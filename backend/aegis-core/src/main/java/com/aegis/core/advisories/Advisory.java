package com.aegis.core.advisories;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A normalised advisory — one feed entry reduced to the parts the pipeline uses: the CVSS metric that
 * scoring consumes and the affected products (CPE- or purl-shaped) that matching resolves against
 * registered components.
 *
 * <p>Feed-agnostic: {@code sourceId} distinguishes NVD from OSV and vendor feeds, and {@code externalId}
 * is that feed's own identifier (e.g. {@code CVE-2021-44228}, {@code GHSA-jfh8-c2jp-5v3q}). An advisory
 * is identified by {@code (sourceId, externalId)}; a revised entry updates the same record.
 *
 * <p>{@code canonicalId} is the vulnerability's cross-source identity: the CVE id when it has one
 * (NVD's external id, or an OSV record's CVE alias), otherwise the record's own id. Advisories for the
 * same real-world vulnerability reported by different feeds share a {@code canonicalId}, which the
 * change-signals feed dedups on and matching expands a signal back to.
 *
 * <p>{@code id} is null on a freshly parsed advisory — the store assigns it on first write.
 */
public record Advisory(
        UUID id,
        String sourceId,
        String externalId,
        String canonicalId,
        String description,
        Instant publishedAt,
        Instant lastModifiedAt,
        Cvss cvss,
        List<AffectedProduct> affected) {

    public Advisory {
        canonicalId = canonicalId == null || canonicalId.isBlank() ? externalId : canonicalId;
        affected = affected == null ? List.of() : List.copyOf(affected);
    }

    /** For single-identity feeds (NVD) whose external id already is the canonical (CVE) id. */
    public Advisory(
            UUID id,
            String sourceId,
            String externalId,
            String description,
            Instant publishedAt,
            Instant lastModifiedAt,
            Cvss cvss,
            List<AffectedProduct> affected) {
        this(id, sourceId, externalId, externalId, description, publishedAt, lastModifiedAt, cvss, affected);
    }

    /**
     * The advisory's primary severity metric. A feed may publish several (CVSS v3.1, v3.0, v2, or a
     * vendor label); the connector keeps one, because scoring needs a single comparable number. Null
     * when the feed published none, which is normal for a freshly disclosed CVE awaiting analysis.
     */
    public record Cvss(String version, String vectorString, BigDecimal baseScore, String severity) {
    }
}
