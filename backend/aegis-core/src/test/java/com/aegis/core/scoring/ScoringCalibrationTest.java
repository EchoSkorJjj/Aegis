package com.aegis.core.scoring;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import com.aegis.core.inventory.demo.DemoEstateSeeder;
import com.aegis.core.inventory.demo.DemoEstateSeeder.Estate;
import com.aegis.core.inventory.demo.DemoEstateSeeder.ServiceSpec;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.inventory.sbom.CycloneDxBom;
import com.aegis.core.inventory.sbom.CycloneDxBom.Component;
import com.aegis.core.inventory.sbom.PackageUrl;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.scoring.ScoringDtos.ScoreBreakdown;
import com.aegis.core.scoring.ScoringDtos.SeverityInput;
import com.aegis.core.signals.ChangeSignal.SignalType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import static com.aegis.core.matching.MatchingDtos.MatchConfidence.CONFIRMED;
import static com.aegis.core.scoring.ScoringDtos.ImpactTier.CRITICAL;
import static com.aegis.core.scoring.ScoringDtos.ImpactTier.HIGH;
import static com.aegis.core.scoring.ScoringDtos.ImpactTier.LOW;
import static com.aegis.core.scoring.ScoringDtos.ImpactTier.MEDIUM;
import static com.aegis.core.scoring.ScoringDtos.ImpactTier.NEGLIGIBLE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The impact model's calibration against the demo dataset (CS203-73) — the executable half of
 * {@code SCORING-CALIBRATION.md}. Two kinds of check, both against the weighting that actually ships
 * (bound from {@code application.yml}, not from Java defaults):
 *
 * <ul>
 *   <li><b>Principles</b> (P1-P5) hold for every service profile and at every CVSS band edge, so the
 *       model is right in general, not only for five services.</li>
 *   <li><b>The demo dataset</b>: the real CVEs the demo SBOMs' pinned versions are exposed to, and the
 *       vendor outages its vendors can raise, scored against the demo services as {@code estate.json}
 *       declares them — including the reference judgments (R1-R4) a reviewer signed off on, and the
 *       full reviewed table.</li>
 * </ul>
 *
 * <p>A retune that breaks any of this fails here, by name, instead of silently re-ranking every
 * organization's queue. Changing a weight on purpose means updating the reviewed table below and the
 * calibration document together — which is the point.
 */
class ScoringCalibrationTest {

    private static final Logger log = LoggerFactory.getLogger(ScoringCalibrationTest.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * Real CVEs affecting versions pinned in the demo SBOMs, with their NVD CVSS v3.1 base scores.
     * Which demo services carry each one is not written here — it is read off the SBOMs.
     */
    private static final List<Cve> DEMO_CVES = List.of(
            new Cve("CVE-2021-44228", "Log4Shell", "10.0", "maven", "org.apache.logging.log4j:log4j-core", "2.14.1"),
            new Cve("CVE-2022-22965", "Spring4Shell", "9.8", "maven", "org.springframework:spring-webmvc", "5.3.13"),
            new Cve("CVE-2022-21724", "pgjdbc plugin instantiation", "9.8", "maven", "org.postgresql:postgresql", "42.3.1"),
            new Cve("CVE-2021-45046", "Log4j lookup bypass", "9.0", "maven", "org.apache.logging.log4j:log4j-core", "2.14.1"),
            new Cve("CVE-2020-36518", "jackson-databind deep nesting", "7.5", "maven", "com.fasterxml.jackson.core:jackson-databind", "2.13.0"),
            new Cve("CVE-2021-3749", "axios ReDoS", "7.5", "npm", "axios", "0.21.1"),
            new Cve("CVE-2021-33503", "urllib3 ReDoS", "7.5", "pypi", "urllib3", "1.26.4"),
            new Cve("CVE-2021-23337", "lodash command injection", "7.2", "npm", "lodash", "4.17.20"),
            new Cve("CVE-2021-44832", "Log4j JDBC appender RCE", "6.6", "maven", "org.apache.logging.log4j:log4j-core", "2.14.1"),
            new Cve("CVE-2023-32681", "requests proxy credential leak", "6.1", "pypi", "requests", "2.25.1"),
            new Cve("CVE-2021-45105", "Log4j recursion DoS", "5.9", "maven", "org.apache.logging.log4j:log4j-core", "2.14.1"),
            new Cve("CVE-2022-0536", "follow-redirects header leak", "5.9", "npm", "follow-redirects", "1.14.7"),
            new Cve("CVE-2020-28500", "lodash ReDoS", "5.3", "npm", "lodash", "4.17.20"));

    /** Outages the demo's vendors can raise, by the impact word their feed uses (AWS's RSS has none). */
    private static final List<Outage> DEMO_OUTAGES = List.of(
            new Outage("Stripe", "major"),
            new Outage("Auth0", "critical"),
            new Outage("Amazon Web Services", null),
            new Outage("Snowflake", "major"),
            new Outage("Cloudflare", "minor"),
            new Outage("Cloudflare", "none"));

    /**
     * The reviewed outcome for every (signal, demo service) pair, highest first — the table in
     * {@code SCORING-CALIBRATION.md}. Keyed "signal @ service".
     */
    private static final Map<String, Expected> REVIEWED = Map.ofEntries(
            reviewed("CVE-2021-44228 @ Payments API", 100.0, CRITICAL),
            reviewed("CVE-2022-22965 @ Payments API", 98.0, CRITICAL),
            reviewed("CVE-2022-21724 @ Payments API", 98.0, CRITICAL),
            reviewed("CVE-2021-45046 @ Payments API", 90.0, CRITICAL),
            reviewed("Auth0 critical outage @ Customer Portal", 76.0, HIGH),
            reviewed("CVE-2020-36518 @ Payments API", 75.0, HIGH),
            reviewed("Stripe major outage @ Payments API", 75.0, HIGH),
            reviewed("CVE-2021-44228 @ Order Service", 72.0, HIGH),
            reviewed("CVE-2021-44832 @ Payments API", 66.0, MEDIUM),
            reviewed("CVE-2021-45046 @ Order Service", 64.8, MEDIUM),
            reviewed("CVE-2021-3749 @ Customer Portal", 60.0, MEDIUM),
            reviewed("CVE-2021-45105 @ Payments API", 59.0, MEDIUM),
            reviewed("CVE-2021-23337 @ Customer Portal", 57.6, MEDIUM),
            reviewed("CVE-2020-36518 @ Order Service", 54.0, MEDIUM),
            reviewed("Amazon Web Services unlabelled outage @ Payments API", 50.0, MEDIUM),
            reviewed("CVE-2021-44832 @ Order Service", 47.5, MEDIUM),
            reviewed("CVE-2022-0536 @ Customer Portal", 47.2, MEDIUM),
            reviewed("CVE-2021-45105 @ Order Service", 42.5, MEDIUM),
            reviewed("CVE-2020-28500 @ Customer Portal", 42.4, MEDIUM),
            reviewed("Amazon Web Services unlabelled outage @ Order Service", 40.0, MEDIUM),
            reviewed("Cloudflare minor outage @ Customer Portal", 40.0, MEDIUM),
            reviewed("Cloudflare minor outage @ Marketing Site", 25.0, LOW),
            reviewed("Snowflake major outage @ Internal Reporting", 18.8, LOW),
            reviewed("CVE-2021-33503 @ Internal Reporting", 9.4, NEGLIGIBLE),
            reviewed("CVE-2023-32681 @ Internal Reporting", 7.6, NEGLIGIBLE),
            reviewed("Cloudflare none outage @ Customer Portal", 4.0, NEGLIGIBLE),
            reviewed("Cloudflare none outage @ Marketing Site", 2.5, NEGLIGIBLE));

    /** CVSS band edges, from the lowest base score v3.1 can produce (1.6) to the top of the scale. */
    private static final List<String> CVSS_EDGES = List.of("1.6", "3.9", "4.0", "6.9", "7.0", "8.9", "9.0", "10.0");

    private static ScoringProperties shipped;
    private static ImpactScorer scorer;
    private static List<ServiceSpec> services;
    private static List<Row> demo;

    @BeforeAll
    static void scoreTheDemoDatasetWithTheShippedWeights() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        shipped = Binder.get(environment).bind("aegis.scoring", ScoringProperties.class).get();
        scorer = new ImpactScorer(shipped);

        services = read(DemoEstateSeeder.ESTATE_RESOURCE, Estate.class).services();
        demo = new ArrayList<>();
        for (Cve cve : DEMO_CVES) {
            for (ServiceSpec service : services) {
                if (carries(service, cve)) {
                    demo.add(row(cve.id(), cve.cvssRating(), service, SignalType.VULNERABILITY,
                            scorer.severityFromCvss("3.1", new BigDecimal(cve.cvss()))));
                }
            }
        }
        for (Outage outage : DEMO_OUTAGES) {
            for (ServiceSpec service : services) {
                if (service.vendors().contains(outage.vendor())) {
                    demo.add(row(outage.name(), outage.label() == null ? "no label" : outage.label(), service,
                            SignalType.VENDOR_OUTAGE, scorer.severityFromLabel(outage.label())));
                }
            }
        }
        demo.sort(Comparator.comparingDouble(Row::score).reversed().thenComparing(Row::key));
        log.info("Impact model calibrated against the demo dataset (score, tier, pair):\n{}", table());
    }

    @Test
    void theShippedConfigurationIsTheCalibratedModel() {
        // application.yml restates every default as ${ENV:default}; if the two drift, what ships is
        // not what was calibrated.
        assertThat(shipped).isEqualTo(ScoringProperties.defaults());
    }

    // ---- Principles: hold for every service profile, not just the demo's -------------------------

    @Test
    void p1OnAWorstCaseServiceTheTierIsThePublishedCvssRating() {
        for (String cvss : CVSS_EDGES) {
            assertThat(tierOf(cvssScore(cvss, BusinessCriticality.CRITICAL, ServiceExposure.INTERNET_FACING)))
                    .as("CVSS %s on a CRITICAL internet-facing service", cvss)
                    .isEqualTo(cvssRating(cvss));
        }
    }

    @Test
    void p1bOnAWorstCaseServiceASeverityWordLandsInTheTierItNames() {
        Map<String, ImpactTier> named = Map.ofEntries(
                Map.entry("critical", CRITICAL), Map.entry("high", HIGH), Map.entry("major", HIGH),
                Map.entry("important", HIGH), Map.entry("medium", MEDIUM), Map.entry("moderate", MEDIUM),
                Map.entry("minor", MEDIUM), Map.entry("low", LOW), Map.entry("none", NEGLIGIBLE),
                Map.entry("maintenance", NEGLIGIBLE), Map.entry("informational", NEGLIGIBLE));
        named.forEach((word, tier) -> assertThat(tierOf(labelScore(word, SignalType.VENDOR_OUTAGE,
                        BusinessCriticality.CRITICAL, ServiceExposure.INTERNET_FACING)))
                .as("'%s' on a CRITICAL internet-facing service", word)
                .isEqualTo(tier));
    }

    @Test
    void p2EachStepBelowTheWorstCaseCostsAtMostOneBand() {
        for (BusinessCriticality criticality : BusinessCriticality.values()) {
            for (ServiceExposure exposure : ServiceExposure.values()) {
                int steps = (BusinessCriticality.CRITICAL.ordinal() - criticality.ordinal())
                        + (ServiceExposure.INTERNET_FACING.ordinal() - exposure.ordinal());
                for (String cvss : CVSS_EDGES) {
                    int floor = Math.max(cvssRating(cvss).ordinal() - steps, 0);
                    assertThat(tierOf(cvssScore(cvss, criticality, exposure)).ordinal())
                            .as("CVSS %s on %s/%s (%d steps below the worst case)", cvss, criticality, exposure, steps)
                            .isGreaterThanOrEqualTo(floor);
                }
            }
        }
    }

    @Test
    void p3ACriticalCveIsNeverImmaterialOnAnyService() {
        for (BusinessCriticality criticality : BusinessCriticality.values()) {
            for (ServiceExposure exposure : ServiceExposure.values()) {
                assertThat(scorer.material(cvssScore("9.0", criticality, exposure)))
                        .as("CVSS 9.0 on %s/%s", criticality, exposure)
                        .isTrue();
            }
        }
    }

    @Test
    void p4AMediumCveOnTheLowestValueServiceIsNoise() {
        assertThat(scorer.material(cvssScore("6.9", BusinessCriticality.LOW, ServiceExposure.ISOLATED))).isFalse();
    }

    @Test
    void p5AnOutageIsNotDiscountedForExposure() {
        for (BusinessCriticality criticality : BusinessCriticality.values()) {
            double internetFacing = labelScore("major", SignalType.VENDOR_OUTAGE, criticality, ServiceExposure.INTERNET_FACING);
            for (ServiceExposure exposure : ServiceExposure.values()) {
                assertThat(labelScore("major", SignalType.VENDOR_OUTAGE, criticality, exposure))
                        .as("a major outage on a %s/%s service", criticality, exposure)
                        .isEqualTo(internetFacing);
            }
        }
    }

    // ---- The demo dataset ---------------------------------------------------------------------------

    @Test
    void theDemoDatasetIsWhatTheCalibrationAssumes() {
        // The reference judgments below are about these services as declared — if the demo estate is
        // re-graded, the calibration has to be re-reviewed, not silently re-run.
        assertThat(services).extracting(service -> "%s %s/%s".formatted(
                        service.name(), service.criticality(), service.exposure()))
                .containsExactlyInAnyOrder(
                        "Payments API CRITICAL/INTERNET_FACING",
                        "Order Service HIGH/INTERNAL",
                        "Customer Portal HIGH/INTERNET_FACING",
                        "Internal Reporting LOW/ISOLATED",
                        "Marketing Site MEDIUM/INTERNET_FACING");
        // Every calibration CVE is carried by at least one demo service, read off the SBOMs.
        for (Cve cve : DEMO_CVES) {
            assertThat(demo).as("%s %s@%s in a demo SBOM", cve.id(), cve.name(), cve.version())
                    .anyMatch(row -> row.signal().equals(cve.id()));
        }
    }

    @Test
    void referenceJudgmentsOnTheDemoDataset() {
        // R1: the demo's headline case. A maximum-severity, network-exploitable RCE on a HIGH-tier
        // service must not drop to MEDIUM just because that service is internal.
        assertThat(pair("CVE-2021-44228 @ Order Service").tier()).isEqualTo(HIGH);
        assertThat(demo.getFirst().key()).isEqualTo("CVE-2021-44228 @ Payments API");
        // R2: Stripe's "major" is degraded payments, not a total loss — HIGH, not CRITICAL.
        assertThat(pair("Stripe major outage @ Payments API").tier()).isEqualTo(HIGH);
        // R3: the reporting tool's warehouse being down is low-value but real, so it stays material.
        assertThat(pair("Snowflake major outage @ Internal Reporting").material()).isTrue();
        // R4: a notice that reports no impact is never queued, on any service.
        assertThat(demo).filteredOn(row -> row.severity().equals("none")).isNotEmpty()
                .noneMatch(Row::material);
    }

    @Test
    void theDemoDatasetScoresAsReviewed() {
        assertThat(demo).extracting(Row::key).containsExactlyInAnyOrderElementsOf(REVIEWED.keySet());
        for (Row row : demo) {
            Expected expected = REVIEWED.get(row.key());
            assertThat(row.score()).as(row.key()).isEqualTo(expected.score());
            assertThat(row.tier()).as(row.key()).isEqualTo(expected.tier());
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private static Row row(String signal, String severity, ServiceSpec service, SignalType type, SeverityInput input) {
        ScoreBreakdown breakdown = scorer.breakdown(
                type, input.factor(), service.exposure(), service.criticality(), CONFIRMED);
        double score = scorer.score(breakdown);
        return new Row(signal, severity, service.name(), score, scorer.tier(score), scorer.material(score));
    }

    private static double cvssScore(String cvss, BusinessCriticality criticality, ServiceExposure exposure) {
        SeverityInput severity = scorer.severityFromCvss("3.1", new BigDecimal(cvss));
        return scorer.score(scorer.breakdown(SignalType.VULNERABILITY, severity.factor(), exposure, criticality, CONFIRMED));
    }

    private static double labelScore(
            String label, SignalType type, BusinessCriticality criticality, ServiceExposure exposure) {
        SeverityInput severity = scorer.severityFromLabel(label);
        return scorer.score(scorer.breakdown(type, severity.factor(), exposure, criticality, CONFIRMED));
    }

    private static ImpactTier tierOf(double score) {
        return scorer.tier(score);
    }

    /** The CVSS v3.1 qualitative rating of a base score, expressed as the tier it should become. */
    private static ImpactTier cvssRating(String cvss) {
        double score = Double.parseDouble(cvss);
        if (score >= 9.0) {
            return CRITICAL;
        }
        if (score >= 7.0) {
            return HIGH;
        }
        if (score >= 4.0) {
            return MEDIUM;
        }
        return score > 0 ? LOW : NEGLIGIBLE;
    }

    private static boolean carries(ServiceSpec service, Cve cve) {
        if (service.sbom() == null) {
            return false;
        }
        CycloneDxBom bom = read(service.sbom(), CycloneDxBom.class);
        return flatten(bom.components())
                .map(component -> PackageUrl.parse(component.purl()))
                .anyMatch(purl -> purl.type().equals(cve.ecosystem())
                        && purl.packageName().equals(cve.name())
                        && Objects.equals(purl.version(), cve.version()));
    }

    private static Stream<Component> flatten(List<Component> components) {
        return components == null
                ? Stream.empty()
                : components.stream().flatMap(component -> Stream.concat(Stream.of(component), flatten(component.components())));
    }

    private static Row pair(String key) {
        return demo.stream().filter(row -> row.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("No demo pair " + key));
    }

    private static String table() {
        StringBuilder table = new StringBuilder();
        for (Row row : demo) {
            table.append(String.format(Locale.ROOT, "  %5.1f  %-10s %-12s %-20s %s [%s]%n",
                    row.score(), row.tier(), row.material() ? "" : "(immaterial)", row.service(), row.signal(),
                    row.severity()));
        }
        return table.toString();
    }

    private static <T> T read(String resource, Class<T> type) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return JSON.readValue(in, type);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read " + resource, ex);
        }
    }

    private static Map.Entry<String, Expected> reviewed(String key, double score, ImpactTier tier) {
        return Map.entry(key, new Expected(score, tier));
    }

    private record Cve(String id, String nickname, String cvss, String ecosystem, String name, String version) {
        String cvssRating() {
            return "CVSS " + cvss;
        }
    }

    private record Outage(String vendor, String label) {
        String name() {
            return "%s %s outage".formatted(vendor, label == null ? "unlabelled" : label);
        }
    }

    private record Row(String signal, String severity, String service, double score, ImpactTier tier, boolean material) {
        String key() {
            return signal + " @ " + service;
        }
    }

    private record Expected(double score, ImpactTier tier) {
    }
}
