package com.aegis.core.advisories;

import java.util.Collection;
import java.util.List;
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

    /**
     * Every advisory sharing a canonical (cross-source) id — the same real-world vulnerability as
     * reported by NVD, OSV, and any other feed. Matching unions their affected products so a CVE is
     * matched with both its CPE (NVD) and its purl (OSV) coordinates.
     */
    List<Advisory> findByCanonicalId(String canonicalId);

    /** {@link #findByCanonicalId} for many vulnerabilities in a few queries, however many are asked for. */
    List<Advisory> findByCanonicalIds(Collection<String> canonicalIds);

    /**
     * Every advisory with an affected product that could name one package: a purl whose package name,
     * lower-cased with each run of {@code - _ .} folded to {@code -}, is one of {@code packageKeys}, or a
     * CPE whose product, lower-cased with each run of non-alphanumerics folded to {@code -} and trimmed of
     * {@code -}, is one of {@code productKeys}. A coarse, index-backed prefilter: the caller applies the
     * exact identity rules. Each advisory comes back with all of its affected products.
     */
    List<Advisory> findNamingPackage(Collection<String> packageKeys, Collection<String> productKeys);

    /** A page of advisories, newest revision first, each with its affected products. */
    Page<Advisory> findPage(Pageable pageable);
}
