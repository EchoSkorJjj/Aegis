package com.aegis.core.scoring;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.aegis.core.inventory.CodeUsage;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The impact model's tunables ({@code aegis.scoring.*}) — every judgement call in scoring, in one
 * place and overridable per deployment.
 *
 * <p>They are configuration rather than constants because the arithmetic is not the part anyone
 * argues with: the weighting is. One organization's LOW-tier internal tool is another's revenue path,
 * and a security team that wants a stricter queue should be able to move the CRITICAL band without a
 * rebuild. Every value has a default, so an empty {@code aegis.scoring} block behaves exactly like the
 * shipped model and a partial override only changes what it names.
 *
 * <p>Each factor is a multiplier in {@code [0,1]}, so the product is too and the score stays a plain
 * percentage of "the worst this could be". Defaults are relative, not absolute: an internet-facing
 * service is worth 2x an isolated one, a CRITICAL tier 4x a LOW one.
 *
 * <p><b>Calibration (CS203-73).</b> The shipped defaults are calibrated against the demo dataset, not
 * picked by feel: they are the smallest change to the original weighting that makes every calibration
 * anchor hold — e.g. on a worst-case service the tier is the published CVSS rating, and Log4Shell on
 * the demo's internal Order Service is HIGH. The anchors, the demo results, and why each number is
 * what it is live in {@code SCORING-CALIBRATION.md}; {@code ScoringCalibrationTest} enforces them, so
 * a retune that breaks one fails the build rather than quietly re-ranking the queue.
 *
 * <p><b>Reachability (E11-4).</b> A vulnerability in a package the service's code never imports, nor anything that
 * pulls it in, is discounted by {@code reachability.not-imported}. An unknown reachability always scores like
 * {@code reachability.imported} — no score drops without evidence — so it has no weight of its own, and not imported
 * can never weigh more than imported.
 *
 * <p><b>Validation.</b> Absent values take the default. A present value that is out of range is
 * handled by its kind: a single weight or threshold has a safe reading, so it is clamped into range
 * and logged (a stray {@code 5.0} multiplier becomes {@code 1.0}, not a silent score of infinity).
 * The tier ladder is the exception — a non-descending ladder has no safe reading, since every score
 * above the inversion would mis-tier, so it fails fast at startup rather than triaging wrongly in
 * silence (see {@link Tiers}).
 */
@ConfigurationProperties("aegis.scoring")
public record ScoringProperties(
        Map<BusinessCriticality, Double> criticality,
        Map<ServiceExposure, Double> exposure,
        Map<MatchConfidence, Double> confidence,
        Map<String, Double> severityLabel,
        Double unknownSeverityFactor,
        Tiers tiers,
        Double materialityThreshold,
        Rollup rollup,
        Map<CodeUsage.Status, Double> reachability) {

    private static final Logger log = LoggerFactory.getLogger(ScoringProperties.class);

    // Every scalar is boxed on purpose. An unset property binds a primitive to 0, which is
    // indistinguishable from a deliberate 0 — and for materialityThreshold a silent 0 would mark
    // every result material, i.e. turn the noise control off without anyone configuring that. Null
    // means "not configured" and takes the default; a present 0 is honoured where 0 is meaningful
    // (materialityThreshold), and floored where it is not (unknownSeverityFactor — an unknown severity
    // must never score as harmless).
    public ScoringProperties {
        criticality = mergedFactors(defaultCriticality(), criticality, BusinessCriticality.class, "criticality");
        exposure = mergedFactors(defaultExposure(), exposure, ServiceExposure.class, "exposure");
        confidence = mergedFactors(defaultConfidence(), confidence, MatchConfidence.class, "confidence");
        severityLabel = mergedLabelFactors(defaultSeverityLabels(), severityLabel);
        unknownSeverityFactor = severityFloor(unknownSeverityFactor);
        if (tiers == null) {
            tiers = new Tiers(null, null, null, null);
        }
        materialityThreshold = boundedThreshold(materialityThreshold);
        if (rollup == null) {
            rollup = new Rollup(null, null);
        }
        reachability = reachabilityFactors(reachability);
    }

    /** The shipped weighting — what an empty {@code aegis.scoring} block binds to. */
    public static ScoringProperties defaults() {
        return new ScoringProperties(null, null, null, null, null, null, null, null, null);
    }

    /**
     * An override names only the entries it wants to change, so a deployment that cares about one
     * tier does not have to restate the whole table (and cannot silently drop an enum constant added
     * by a later story). Each overriding value is clamped into {@code [0,1]} — a weight outside that
     * range would break the "a percentage of the worst case" invariant the whole model rests on.
     */
    private static <K extends Enum<K>> Map<K, Double> mergedFactors(
            Map<K, Double> defaults, Map<K, Double> overrides, Class<K> type, String name) {
        if (overrides == null || overrides.isEmpty()) {
            return defaults;
        }
        Map<K, Double> merged = new EnumMap<>(type);
        merged.putAll(defaults);
        overrides.forEach((key, value) -> {
            if (key != null && value != null) {
                merged.put(key, clampFactor(value, "aegis.scoring." + name + "." + key));
            }
        });
        return Map.copyOf(merged);
    }

    /**
     * The reachability table: imported and not imported, merged like the others. Unknown has no weight of its own (it
     * scores like imported), so a configured one is ignored; not imported weighing more than imported would rank the
     * code that never reaches a package above the code that does, so it is held at imported's weight.
     */
    private static Map<CodeUsage.Status, Double> reachabilityFactors(Map<CodeUsage.Status, Double> overrides) {
        Map<CodeUsage.Status, Double> weighted = new EnumMap<>(CodeUsage.Status.class);
        if (overrides != null) {
            overrides.forEach((status, value) -> {
                if (status == CodeUsage.Status.UNKNOWN) {
                    log.warn("aegis.scoring.reachability.unknown is ignored: an unknown reachability scores like "
                            + "aegis.scoring.reachability.imported");
                } else if (status != null && value != null) {
                    weighted.put(status, value);
                }
            });
        }
        Map<CodeUsage.Status, Double> merged = new EnumMap<>(CodeUsage.Status.class);
        merged.putAll(mergedFactors(defaultReachability(), weighted, CodeUsage.Status.class, "reachability"));
        double imported = merged.get(CodeUsage.Status.IMPORTED);
        if (merged.get(CodeUsage.Status.NOT_IMPORTED) > imported) {
            log.warn("aegis.scoring.reachability.not-imported = {} is above imported ({}); using {}",
                    merged.get(CodeUsage.Status.NOT_IMPORTED), imported, imported);
            merged.put(CodeUsage.Status.NOT_IMPORTED, imported);
        }
        return Map.copyOf(merged);
    }

    /** The string-keyed twin of {@link #mergedFactors} for the severity-label table; keys fold to lower case. */
    private static Map<String, Double> mergedLabelFactors(
            Map<String, Double> defaults, Map<String, Double> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return defaults;
        }
        Map<String, Double> merged = new HashMap<>(defaults);
        overrides.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                String label = key.trim().toLowerCase(Locale.ROOT);
                merged.put(label, clampFactor(value, "aegis.scoring.severity-label." + label));
            }
        });
        return Map.copyOf(merged);
    }

    /** Clamp a multiplier into {@code [0,1]}, logging when a configured value had to be corrected. */
    private static double clampFactor(double value, String what) {
        double clamped = Math.min(Math.max(value, 0.0), 1.0);
        if (clamped != value) {
            log.warn("{} = {} is outside [0,1]; using {}", what, value, clamped);
        }
        return clamped;
    }

    /** Absent or non-positive → the neutral 0.5 (never harmless); above 1 → clamped to 1 with a warning. */
    private static double severityFloor(Double value) {
        if (value == null || value <= 0) {
            return 0.5;
        }
        if (value > 1) {
            log.warn("aegis.scoring.unknown-severity-factor = {} is above 1; using 1.0", value);
            return 1.0;
        }
        return value;
    }

    /** Absent or negative → the default 10; a real 0 is honoured (everything material); above 100 → clamped. */
    private static double boundedThreshold(Double value) {
        if (value == null || value < 0) {
            return 10.0;
        }
        if (value > 100) {
            log.warn("aegis.scoring.materiality-threshold = {} is above 100; using 100.0", value);
            return 100.0;
        }
        return value;
    }

    /**
     * Lower bounds of each impact tier on the 0-100 scale, highest first. A score at or above
     * {@code critical} is CRITICAL, at or above {@code high} is HIGH, and so on; anything below
     * {@code low} is NEGLIGIBLE.
     *
     * <p>The defaults are the CVSS v3.1 qualitative bands scaled to 0-100 (Critical 9.0, High 7.0,
     * Medium 4.0, Low from the 1.0 the materiality threshold starts at), so on a service where nothing
     * mitigates — CRITICAL tier, internet-facing — the impact tier is exactly the published rating, and
     * every discount below that is the service's context speaking. The earlier quartile ladder
     * (75/50/25) promoted every worst-case score a band above its CVSS rating: a 7.5 read CRITICAL.
     *
     * <p>Unlike a single weight, a tier ladder cannot be clamped to something sensible: if the bounds
     * are not descending, or exceed the top of the scale, every score above the inversion mis-tiers
     * and there is no safe value to substitute. A broken ladder therefore throws — surfacing as a
     * startup failure with the offending values — rather than triaging wrongly in silence. Absent
     * bounds still take the shipped defaults, so an empty or partial block never trips this.
     */
    public record Tiers(Double critical, Double high, Double medium, Double low) {

        public Tiers {
            if (critical == null || critical < 0) {
                critical = 90.0;
            }
            if (high == null || high < 0) {
                high = 70.0;
            }
            if (medium == null || medium < 0) {
                medium = 40.0;
            }
            if (low == null || low < 0) {
                low = 10.0;
            }
            if (critical > 100 || !(critical >= high && high >= medium && medium >= low)) {
                throw new IllegalArgumentException(
                        ("aegis.scoring.tiers must be descending bounds within [0,100] "
                                + "(critical >= high >= medium >= low, critical <= 100), "
                                + "got critical=%s high=%s medium=%s low=%s")
                                .formatted(critical, high, medium, low));
            }
        }
    }

    /**
     * Bounds on the estate-wide roll-up, which scores many signals in one request.
     *
     * @param maxSignals    how many change signals one roll-up may score. Each signal costs a match
     *                      and a graph traversal, so this is a hard ceiling on the work a single
     *                      request can ask for, not a page size — the newest signals in the window win.
     * @param defaultWindow how far back a roll-up reaches when the caller gives no {@code from}. A
     *                      roll-up answers "what is on our plate now", so it is deliberately recent.
     */
    public record Rollup(Integer maxSignals, Duration defaultWindow) {

        public Rollup {
            if (maxSignals == null || maxSignals < 1) {
                maxSignals = 200;
            }
            if (defaultWindow == null) {
                defaultWindow = Duration.ofDays(30);
            }
        }
    }

    /**
     * How much of an advisory's severity a service's business tier retains. A vulnerability in a
     * CRITICAL revenue path is the full number; the same one in a LOW internal tool is a quarter of it.
     *
     * <p>HIGH is 0.8 rather than the original 0.75: one step below the worst case may cost a critical
     * CVE at most one band (9.0 x 0.8 = 72, still HIGH), and together with INTERNAL's 0.9 it keeps
     * Log4Shell on the demo's internal Order Service at HIGH (72) rather than MEDIUM.
     */
    private static Map<BusinessCriticality, Double> defaultCriticality() {
        Map<BusinessCriticality, Double> weights = new EnumMap<>(BusinessCriticality.class);
        weights.put(BusinessCriticality.CRITICAL, 1.0);
        weights.put(BusinessCriticality.HIGH, 0.8);
        weights.put(BusinessCriticality.MEDIUM, 0.5);
        weights.put(BusinessCriticality.LOW, 0.25);
        return Map.copyOf(weights);
    }

    /**
     * How much an attacker's reach is worth. An isolated service is not invulnerable — an insider or a
     * compromised neighbour still reaches it — so the floor is well above zero, not 0.
     *
     * <p>Exposure is CVSS's Attack Vector by another name, so INTERNAL takes CVSS v3.1's own discount:
     * re-scoring a critical network vector (AV:N/AC:L/PR:N/UI:N, 9.8) as Adjacent gives 8.8 — 0.9 of
     * it. The original 0.6 punished internal services four times harder than CVSS itself does. ISOLATED
     * is set by the noise window instead (see {@code SCORING-CALIBRATION.md}): on a LOW-tier isolated
     * service a critical CVE must stay material and a medium one must not, which 0.5 x 0.25 satisfies
     * with a point of margin either side.
     *
     * <p>Exposure measures inbound attack reach, so it applies to vulnerabilities only — a vendor outage
     * takes an internal service down exactly as surely as an internet-facing one (see
     * {@link ImpactScorer#breakdown}).
     */
    private static Map<ServiceExposure, Double> defaultExposure() {
        Map<ServiceExposure, Double> weights = new EnumMap<>(ServiceExposure.class);
        weights.put(ServiceExposure.INTERNET_FACING, 1.0);
        weights.put(ServiceExposure.INTERNAL, 0.9);
        weights.put(ServiceExposure.ISOLATED, 0.5);
        return Map.copyOf(weights);
    }

    /**
     * How much a match's confidence discounts the score. Both default to 1.0 — no discount — because
     * confidence answers whether the match applies at all, which is a different question from how bad
     * it is if it does, and a POSSIBLE row is already flagged for review. A deployment that would
     * rather rank unreviewed matches below proven ones can lower POSSIBLE here instead of arguing with
     * the arithmetic.
     */
    private static Map<MatchConfidence, Double> defaultConfidence() {
        Map<MatchConfidence, Double> weights = new EnumMap<>(MatchConfidence.class);
        weights.put(MatchConfidence.CONFIRMED, 1.0);
        weights.put(MatchConfidence.POSSIBLE, 1.0);
        return Map.copyOf(weights);
    }

    /**
     * How much of a vulnerability's score is kept when the service's code never imports the package, nor anything that
     * pulls it in (E11-4). 0.8 is the largest discount that still costs at most one band, like a step on either of the
     * service's own axes (P2): a critical CVE (90) not imported reads HIGH (72), never MEDIUM. Imported is the full
     * score, and an unknown reachability scores like it.
     */
    private static Map<CodeUsage.Status, Double> defaultReachability() {
        Map<CodeUsage.Status, Double> weights = new EnumMap<>(CodeUsage.Status.class);
        weights.put(CodeUsage.Status.IMPORTED, 1.0);
        weights.put(CodeUsage.Status.NOT_IMPORTED, 0.8);
        return Map.copyOf(weights);
    }

    /**
     * Severity factor for a signal with no CVSS, keyed by the feed's severity word (lower-cased). Two
     * vocabularies are shipped — NVD's {@code critical/high/medium/low} and Statuspage's
     * {@code critical/major/minor/none}, plus a few near-synonyms — and a deployment ingesting a feed
     * that speaks a third can add its words here rather than living with the neutral fallback. An
     * unrecognised word still falls back to {@code unknownSeverityFactor}: a label we cannot read must
     * not rank as harmless.
     *
     * <p>Calibrated like CVSS: on a worst-case service each word lands in the tier it names (critical
     * 95 CRITICAL, high/major 75 HIGH, medium/minor 50 MEDIUM, low 25 LOW). The no-impact words sit at
     * 0.05 rather than 0.1 because 0.1 scored exactly the materiality threshold on a worst-case service,
     * so a maintenance notice could land in the queue.
     */
    private static Map<String, Double> defaultSeverityLabels() {
        Map<String, Double> labels = new HashMap<>();
        labels.put("critical", 0.95);
        labels.put("high", 0.75);
        labels.put("major", 0.75);
        labels.put("important", 0.75);
        labels.put("medium", 0.5);
        labels.put("moderate", 0.5);
        labels.put("minor", 0.5);
        labels.put("low", 0.25);
        labels.put("none", 0.05);
        labels.put("maintenance", 0.05);
        labels.put("informational", 0.05);
        return Map.copyOf(labels);
    }

    double criticalityFactor(BusinessCriticality value) {
        return value == null ? 0.5 : criticality.getOrDefault(value, 0.5);
    }

    double exposureFactor(ServiceExposure value) {
        return exposure.getOrDefault(value == null ? BusinessService.DEFAULT_EXPOSURE : value, 0.6);
    }

    double confidenceFactor(MatchConfidence value) {
        return value == null ? 1.0 : confidence.getOrDefault(value, 1.0);
    }

    /** Not applied (null) is 1.0; unknown scores like imported, so no score drops without evidence. */
    double reachabilityFactor(CodeUsage.Status value) {
        if (value == null) {
            return 1.0;
        }
        return reachability.getOrDefault(value == CodeUsage.Status.UNKNOWN ? CodeUsage.Status.IMPORTED : value, 1.0);
    }

    /** The configured factor for a severity word (already lower-cased), or null when it is unrecognised. */
    Double severityLabelFactor(String label) {
        return label == null ? null : severityLabel.get(label);
    }
}
