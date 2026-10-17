package com.aegis.core.ingestion.connectors.osv;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.advisories.CommitUrl;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/**
 * Turns one OSV vulnerability document into a normalised {@link Advisory}: the summary, a severity
 * label, a canonical (CVE) id from the record's aliases, purl-shaped affected products drawn from
 * each package's version ranges, and — for an assessment to read (E11-5) — the details beside the summary
 * and the commits among the references.
 *
 * <p>Like the NVD parser it is deliberately lossy and never compares versions — matching owns
 * ecosystem-specific ordering. Anything dropped is recoverable from the raw-event store.
 */
@Component
class OsvAdvisoryParser {

    /** Column limits in {@code advisories} / {@code advisory_affected_products}. */
    private static final int MAX_ECOSYSTEM = 64;
    private static final int MAX_PACKAGE = 255;
    private static final int MAX_VERSION = 128;
    private static final int MAX_VECTOR = 255;
    private static final int MAX_CVSS_VERSION = 8;
    private static final int MAX_SEVERITY = 16;

    /** A few OSV records enumerate very many versions; keep one advisory from dominating a batch. */
    private static final int MAX_AFFECTED_PRODUCTS = 2_000;

    private final ObjectReader reader;

    OsvAdvisoryParser(ObjectMapper objectMapper) {
        this.reader = objectMapper.reader()
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .forType(OsvJson.Vulnerability.class);
    }

    /**
     * @throws tools.jackson.core.JacksonException the payload is not valid JSON for the OSV schema
     * @throws IllegalArgumentException the document carries no id, so nothing can key it
     */
    Advisory parse(String payload) {
        OsvJson.Vulnerability vuln = reader.readValue(payload);
        if (vuln == null || vuln.id() == null || vuln.id().isBlank()) {
            throw new IllegalArgumentException("OSV document has no id");
        }
        String id = vuln.id().trim();
        return new Advisory(
                null, // assigned by the store on first write
                OsvSource.SOURCE_ID,
                id,
                canonicalId(id, vuln.aliases()),
                description(vuln),
                parseInstant(vuln.published()),
                parseInstant(vuln.modified()),
                cvss(vuln),
                affectedProducts(vuln),
                details(vuln),
                CommitUrl.among(vuln.references() == null ? null
                        : vuln.references().stream().filter(reference -> reference != null)
                                .map(OsvJson.Reference::url).toList()));
    }

    /** The CVE this is — its own id or the first CVE alias — else its own id (GHSA/PYSEC/…). */
    private static String canonicalId(String id, List<String> aliases) {
        if (isCve(id)) {
            return id;
        }
        if (aliases != null) {
            for (String alias : aliases) {
                if (isCve(alias)) {
                    return alias.trim();
                }
            }
        }
        return id;
    }

    private static boolean isCve(String value) {
        return value != null && value.trim().regionMatches(true, 0, "CVE-", 0, 4);
    }

    private static String description(OsvJson.Vulnerability vuln) {
        if (vuln.summary() != null && !vuln.summary().isBlank()) {
            return vuln.summary().trim();
        }
        return vuln.details() == null || vuln.details().isBlank() ? null : vuln.details().trim();
    }

    /** The details beside a summary; with no summary, the details already are the description. */
    private static String details(OsvJson.Vulnerability vuln) {
        if (vuln.summary() == null || vuln.summary().isBlank() || vuln.details() == null || vuln.details().isBlank()) {
            return null;
        }
        return vuln.details().trim();
    }

    /** OSV carries the CVSS vector (not a base score) plus, for GHSA-sourced records, a label severity. */
    private static Advisory.Cvss cvss(OsvJson.Vulnerability vuln) {
        String vector = null;
        if (vuln.severity() != null) {
            for (OsvJson.Severity severity : vuln.severity()) {
                if (severity != null && severity.score() != null && !severity.score().isBlank()) {
                    vector = severity.score().trim();
                    break;
                }
            }
        }
        String label = vuln.databaseSpecific() == null ? null : vuln.databaseSpecific().severity();
        if (vector == null && (label == null || label.isBlank())) {
            return null;
        }
        return new Advisory.Cvss(
                truncate(cvssVersion(vector), MAX_CVSS_VERSION),
                truncate(vector, MAX_VECTOR),
                null,
                label == null || label.isBlank()
                        ? null : truncate(label.trim().toUpperCase(Locale.ROOT), MAX_SEVERITY));
    }

    /** "CVSS:3.1/AV:N/…" → "3.1". */
    private static String cvssVersion(String vector) {
        if (vector == null || !vector.regionMatches(true, 0, "CVSS:", 0, 5)) {
            return null;
        }
        int slash = vector.indexOf('/');
        String version = slash > 5 ? vector.substring(5, slash) : vector.substring(5);
        return version.isBlank() ? null : version;
    }

    private List<AffectedProduct> affectedProducts(OsvJson.Vulnerability vuln) {
        if (vuln.affected() == null) {
            return List.of();
        }
        Set<AffectedProduct> out = new LinkedHashSet<>();
        for (OsvJson.Affected affected : vuln.affected()) {
            if (affected == null || affected.pkg() == null) {
                continue;
            }
            String ecosystem = trimTo(affected.pkg().ecosystem(), MAX_ECOSYSTEM);
            String name = trimTo(affected.pkg().name(), MAX_PACKAGE);
            if (ecosystem == null || name == null) {
                continue;
            }
            collect(out, ecosystem, name, affected);
            if (out.size() >= MAX_AFFECTED_PRODUCTS) {
                return new ArrayList<>(out);
            }
        }
        return new ArrayList<>(out);
    }

    private static void collect(
            Set<AffectedProduct> out, String ecosystem, String name, OsvJson.Affected affected) {
        boolean emitted = false;
        if (affected.ranges() != null) {
            for (OsvJson.Range range : affected.ranges()) {
                if (range == null || range.events() == null || !isVersionRange(range.type())) {
                    continue; // GIT ranges carry commit hashes, not comparable versions
                }
                emitted |= addRange(out, ecosystem, name, range.events());
            }
        }
        if (!emitted && affected.versions() != null && !affected.versions().isEmpty()) {
            for (String candidate : affected.versions()) {
                String version = version(candidate);
                if (version != null) {
                    out.add(AffectedProduct.ofPackage(ecosystem, name, version, null, null, null, null));
                    emitted = true;
                }
                if (out.size() >= MAX_AFFECTED_PRODUCTS) {
                    return;
                }
            }
        }
        if (!emitted) {
            // A package with no comparable version info at all: every version is affected.
            out.add(AffectedProduct.ofPackage(ecosystem, name, null, null, null, null, null));
        }
    }

    private static boolean isVersionRange(String type) {
        return "SEMVER".equalsIgnoreCase(type) || "ECOSYSTEM".equalsIgnoreCase(type);
    }

    /** Walk an ordered event list, emitting one affected product per [introduced, fixed|last_affected) span. */
    private static boolean addRange(
            Set<AffectedProduct> out, String ecosystem, String name, List<OsvJson.Event> events) {
        boolean emitted = false;
        String introduced = null;
        boolean open = false;
        for (OsvJson.Event event : events) {
            if (event == null) {
                continue;
            }
            if (event.introduced() != null) {
                if (open) {
                    out.add(span(ecosystem, name, introduced, null, null)); // previous span had no upper bound
                    emitted = true;
                }
                introduced = "0".equals(event.introduced().trim()) ? null : version(event.introduced());
                open = true;
            } else if (event.fixed() != null) {
                out.add(span(ecosystem, name, introduced, null, version(event.fixed())));
                emitted = true;
                open = false;
                introduced = null;
            } else if (event.lastAffected() != null) {
                out.add(span(ecosystem, name, introduced, version(event.lastAffected()), null));
                emitted = true;
                open = false;
                introduced = null;
            }
        }
        if (open) {
            out.add(span(ecosystem, name, introduced, null, null));
            emitted = true;
        }
        return emitted;
    }

    private static AffectedProduct span(
            String ecosystem, String name, String startIncluding, String endIncluding, String endExcluding) {
        return AffectedProduct.ofPackage(ecosystem, name, null, startIncluding, null, endIncluding, endExcluding);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException notInstant) {
            try {
                return OffsetDateTime.parse(trimmed).toInstant();
            } catch (DateTimeParseException notOffset) {
                return null;
            }
        }
    }

    private static String version(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_VERSION) {
            return null;
        }
        return value.trim();
    }

    private static String trimTo(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > max ? null : trimmed;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
