package com.aegis.core.ingestion.connectors.nvd;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.advisories.CommitUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/**
 * Turns one raw NVD CVE document into a normalised {@link Advisory}: the English description, the
 * primary CVSS metric, and the affected (vendor, product, version-range) tuples drawn from the CPE
 * configurations.
 *
 * <p>Deliberately lossy and deliberately dumb. It selects and reshapes; it never decides whether an
 * advisory matters, and it never compares versions — matching owns version ordering, which is
 * ecosystem-specific. Anything dropped here is still recoverable from the raw-event store.
 */
@Component
class NvdAdvisoryParser {

    /** Column limits in {@code advisories} / {@code advisory_affected_products}. */
    private static final int MAX_VENDOR = 255;
    private static final int MAX_PRODUCT = 255;
    private static final int MAX_VERSION = 128;
    private static final int MAX_VECTOR = 255;
    private static final int MAX_CVSS_VERSION = 8;
    private static final int MAX_SEVERITY = 16;

    /** A handful of NVD CVEs enumerate thousands of CPEs; keep one advisory from dominating a batch. */
    private static final int MAX_AFFECTED_PRODUCTS = 2_000;

    private static final Logger log = LoggerFactory.getLogger(NvdAdvisoryParser.class);

    private final ObjectReader reader;

    NvdAdvisoryParser(ObjectMapper objectMapper) {
        // Explicitly tolerant of unknown fields, so this does not depend on the global mapper's config.
        this.reader = objectMapper.reader()
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .forType(NvdJson.Cve.class);
    }

    /**
     * @throws tools.jackson.core.JacksonException the payload is not valid JSON for the NVD CVE
     *     schema (unchecked — Jackson 3 dropped the checked {@code IOException})
     * @throws IllegalArgumentException the document carries no CVE id, so nothing can key it
     */
    Advisory parse(String payload) {
        NvdJson.Cve cve = reader.readValue(payload);
        if (cve == null || cve.id() == null || cve.id().isBlank()) {
            throw new IllegalArgumentException("NVD document has no CVE id");
        }
        return new Advisory(
                null, // assigned by the store on first write
                NvdSource.SOURCE_ID,
                cve.id().trim(),
                cve.id().trim(), // an NVD id already is the canonical (CVE) id
                description(cve),
                NvdTimestamps.parse(cve.published()),
                NvdTimestamps.parse(cve.lastModified()),
                cvss(cve.metrics()),
                affectedProducts(cve),
                null, // the description already is the full text
                CommitUrl.among(cve.references() == null ? null
                        : cve.references().stream().filter(reference -> reference != null)
                                .map(NvdJson.Reference::url).toList()));
    }

    private static String description(NvdJson.Cve cve) {
        if (cve.descriptions() == null) {
            return null;
        }
        return cve.descriptions().stream()
                .filter(d -> d != null && d.value() != null && !d.value().isBlank())
                .filter(d -> "en".equalsIgnoreCase(d.lang()))
                .map(NvdJson.LangValue::value)
                .findFirst()
                .orElse(null);
    }

    /** Newest scoring generation wins: v3.1, then v3.0, then v2 — scoring needs one comparable number. */
    private static Advisory.Cvss cvss(NvdJson.Metrics metrics) {
        if (metrics == null) {
            return null;
        }
        NvdJson.CvssMetric metric = primary(metrics.cvssMetricV31())
                .or(() -> primary(metrics.cvssMetricV30()))
                .or(() -> primary(metrics.cvssMetricV2()))
                .orElse(null);
        if (metric == null || metric.cvssData() == null || metric.cvssData().baseScore() == null) {
            return null;
        }
        NvdJson.CvssData data = metric.cvssData();
        // v3 carries baseSeverity inside cvssData; v2 carries it on the metric.
        String severity = data.baseSeverity() != null ? data.baseSeverity() : metric.baseSeverity();
        return new Advisory.Cvss(
                truncate(data.version(), MAX_CVSS_VERSION),
                truncate(data.vectorString(), MAX_VECTOR),
                BigDecimal.valueOf(data.baseScore()).setScale(1, RoundingMode.HALF_UP),
                severity == null ? null : truncate(severity.toUpperCase(Locale.ROOT), MAX_SEVERITY));
    }

    /** NVD marks one metric per generation as {@code Primary}; fall back to whatever is first. */
    private static Optional<NvdJson.CvssMetric> primary(List<NvdJson.CvssMetric> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return Optional.empty();
        }
        return metrics.stream()
                .filter(metric -> metric != null && "Primary".equalsIgnoreCase(metric.type()))
                .findFirst()
                .or(() -> metrics.stream().filter(Objects::nonNull).findFirst());
    }

    private List<AffectedProduct> affectedProducts(NvdJson.Cve cve) {
        if (cve.configurations() == null) {
            return List.of();
        }
        Set<AffectedProduct> affected = new LinkedHashSet<>();
        for (NvdJson.Configuration configuration : cve.configurations()) {
            if (configuration == null || configuration.nodes() == null) {
                continue;
            }
            for (NvdJson.Node node : configuration.nodes()) {
                // A negated node lists what is *not* vulnerable.
                if (node == null || node.cpeMatch() == null || Boolean.TRUE.equals(node.negate())) {
                    continue;
                }
                for (NvdJson.CpeMatch match : node.cpeMatch()) {
                    toAffectedProduct(match).ifPresent(affected::add);
                    if (affected.size() >= MAX_AFFECTED_PRODUCTS) {
                        log.warn("nvd: {} lists more than {} affected products; keeping the first {}",
                                cve.id(), MAX_AFFECTED_PRODUCTS, MAX_AFFECTED_PRODUCTS);
                        return new ArrayList<>(affected);
                    }
                }
            }
        }
        return new ArrayList<>(affected);
    }

    private static Optional<AffectedProduct> toAffectedProduct(NvdJson.CpeMatch match) {
        if (match == null || !Boolean.TRUE.equals(match.vulnerable())) {
            return Optional.empty();
        }
        Optional<CpeName> parsed = CpeName.parse(match.criteria());
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        CpeName cpe = parsed.get();
        if (cpe.vendor().length() > MAX_VENDOR || cpe.product().length() > MAX_PRODUCT) {
            return Optional.empty();
        }

        String startIncluding = version(match.versionStartIncluding());
        String startExcluding = version(match.versionStartExcluding());
        String endIncluding = version(match.versionEndIncluding());
        String endExcluding = version(match.versionEndExcluding());
        boolean ranged = startIncluding != null || startExcluding != null
                || endIncluding != null || endExcluding != null;
        // A range and a concrete version are contradictory; NVD means the range when it gives one.
        String exactVersion = ranged ? null : version(cpe.version());

        return Optional.of(new AffectedProduct(
                match.criteria(),
                cpe.part(),
                cpe.vendor(),
                cpe.product(),
                exactVersion,
                startIncluding,
                startExcluding,
                endIncluding,
                endExcluding));
    }

    /** Version strings past the column limit are dropped rather than truncated — a cut version is a lie. */
    private static String version(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_VERSION) {
            return null;
        }
        return value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
