package com.aegis.core.ingestion.connectors.osv;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The slice of the OSV schema (<a href="https://ossf.github.io/osv-schema/">osv-schema</a>) this
 * connector reads. Unknown fields are ignored (the reader is configured that way), and the untouched
 * original is always retrievable from the raw-event store.
 */
final class OsvJson {

    private OsvJson() {
    }

    /** One OSV vulnerability record ({@code {id}.json}). */
    record Vulnerability(
            String id,
            String modified,
            String published,
            String summary,
            String details,
            List<String> aliases,
            List<Severity> severity,
            List<Affected> affected,
            @JsonProperty("database_specific") DatabaseSpecific databaseSpecific,
            List<Reference> references) {
    }

    /** A link the record cites; {@code type} is WEB, ADVISORY, FIX, PACKAGE… (GHSA marks its fix commits WEB). */
    record Reference(String type, String url) {
    }

    /** A CVSS metric: {@code type} like {@code CVSS_V3}, {@code score} the vector string. */
    record Severity(String type, String score) {
    }

    record Affected(
            @JsonProperty("package") Pkg pkg,
            List<Range> ranges,
            List<String> versions,
            @JsonProperty("database_specific") DatabaseSpecific databaseSpecific) {
    }

    record Pkg(String ecosystem, String name, String purl) {
    }

    /** {@code type} is SEMVER, ECOSYSTEM, or GIT; only the first two carry comparable versions. */
    record Range(String type, List<Event> events) {
    }

    /** Exactly one of these is set per event; {@code introduced} "0" means "from the beginning". */
    record Event(
            String introduced,
            String fixed,
            @JsonProperty("last_affected") String lastAffected,
            String limit) {
    }

    /** GHSA and friends put a label severity (LOW/MODERATE/HIGH/CRITICAL) here. */
    record DatabaseSpecific(String severity) {
    }
}
