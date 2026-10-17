package com.aegis.core.ingestion.connectors.nvd;

import java.util.List;

/**
 * The slice of the NVD API 2.0 CVE schema this connector reads. Unknown fields are ignored (the
 * reader is configured that way), so NVD adding fields never breaks ingestion — and the untouched
 * original is always retrievable from the raw-event store.
 */
final class NvdJson {

    private NvdJson() {
    }

    /** One {@code vulnerabilities[].cve} object. */
    record Cve(
            String id,
            String published,
            String lastModified,
            String vulnStatus,
            List<LangValue> descriptions,
            Metrics metrics,
            List<Configuration> configurations,
            List<Reference> references) {
    }

    /** A link the CVE cites, tagged ("Patch", "Vendor Advisory", …). */
    record Reference(String url, List<String> tags) {
    }

    record LangValue(String lang, String value) {
    }

    /** NVD publishes several scoring generations side by side; newest wins (see NvdAdvisoryParser). */
    record Metrics(
            List<CvssMetric> cvssMetricV31,
            List<CvssMetric> cvssMetricV30,
            List<CvssMetric> cvssMetricV2) {
    }

    /** {@code baseSeverity} sits on the metric for CVSS v2 and inside {@code cvssData} for v3. */
    record CvssMetric(String source, String type, CvssData cvssData, String baseSeverity) {
    }

    record CvssData(String version, String vectorString, Double baseScore, String baseSeverity) {
    }

    record Configuration(String operator, Boolean negate, List<Node> nodes) {
    }

    record Node(String operator, Boolean negate, List<CpeMatch> cpeMatch) {
    }

    record CpeMatch(
            Boolean vulnerable,
            String criteria,
            String matchCriteriaId,
            String versionStartIncluding,
            String versionStartExcluding,
            String versionEndIncluding,
            String versionEndExcluding) {
    }
}
