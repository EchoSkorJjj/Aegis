package com.aegis.core.ingestion.connectors.osv;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OSV connector configuration ({@code aegis.ingestion.osv.*}). Disabled by default, like the other
 * feeds. OSV publishes a per-ecosystem bulk export ({@code {baseUrl}/{ecosystem}/all.zip}); the
 * connector downloads only the ecosystems listed in {@code ecosystems}, using the exact OSV bucket
 * names (e.g. {@code npm}, {@code PyPI}, {@code crates.io}, {@code RubyGems}, {@code Go}, {@code Maven}).
 *
 * <p>Bulk snapshots are large, so polling should be infrequent (a day or more); the raw-event store
 * de-duplicates unchanged records so only revisions are re-normalised.
 */
@ConfigurationProperties("aegis.ingestion.osv")
public record OsvProperties(
        boolean enabled,
        String baseUrl,
        List<String> ecosystems,
        Duration connectTimeout,
        Duration readTimeout,
        int maxRecordsPerEcosystem,
        int maxPayloadBytes,
        int normalizeBatchSize,
        boolean allowPrivateHosts,
        boolean includeMalicious) {

    public OsvProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://osv-vulnerabilities.storage.googleapis.com";
        }
        ecosystems = ecosystems == null ? List.of() : List.copyOf(ecosystems);
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            connectTimeout = Duration.ofSeconds(5);
        }
        if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            readTimeout = Duration.ofSeconds(60);
        }
        // A per-poll safety bound: a bucket serving an unexpectedly huge export cannot spin forever.
        if (maxRecordsPerEcosystem < 1) {
            maxRecordsPerEcosystem = 200_000;
        }
        // Untrusted third-party document: cap one record's JSON. OSV records with large version lists
        // run past 1 MB, so this is set generously above the typical size.
        if (maxPayloadBytes < 1) {
            maxPayloadBytes = 4 * 1024 * 1024;
        }
        if (normalizeBatchSize < 1) {
            normalizeBatchSize = 200;
        }
    }
}
