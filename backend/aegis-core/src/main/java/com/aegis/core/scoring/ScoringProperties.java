package com.aegis.core.scoring;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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
 * service is worth 2.5x an isolated one, a CRITICAL tier 4x a LOW one.
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
        Rollup rollup) {

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
    }

    /** The shipped weighting — what an empty {@code aegis.scoring} block binds to. */
    public static ScoringProperties defaults() {
        return new ScoringProperties(null, null, null, null, null, null, null, null);
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
     * <p>Unlike a single weight, a tier ladder cannot be clamped to something sensible: if the bounds
     * are not descending, or exceed the top of the scale, every score above the inversion mis-tiers
     * and there is no safe value to substitute. A broken ladder therefore throws — surfacing as a
     * startup failure with the offending values — rather than triaging wrongly in silence. Absent
     * bounds still take the shipped defaults, so an empty or partial block never trips this.
     */
    public record Tiers(Double critical, Double high, Double medium, Double low) {

        public Tiers {
            if (critical == null || critical < 0) {
                critical = 75.0;
            }
            if (high == null || high < 0) {
                high = 50.0;
            }
            if (medium == null || medium < 0) {
                medium = 25.0;
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
     */
    private static Map<BusinessCriticality, Double> defaultCriticality() {
        Map<BusinessCriticality, Double> weights = new EnumMap<>(BusinessCriticality.class);
        weights.put(BusinessCriticality.CRITICAL, 1.0);
        weights.put(BusinessCriticality.HIGH, 0.75);
        weights.put(BusinessCriticality.MEDIUM, 0.5);
        weights.put(BusinessCriticality.LOW, 0.25);
        return Map.copyOf(weights);
    }

    /**
     * How much an attacker's reach is worth. An isolated service is not invulnerable — an insider or a
     * compromised neighbour still reaches it — so the floor is well above zero, not 0.
     */
    private static Map<ServiceExposure, Double> defaultExposure() {
        Map<ServiceExposure, Double> weights = new EnumMap<>(ServiceExposure.class);
        weights.put(ServiceExposure.INTERNET_FACING, 1.0);
        weights.put(ServiceExposure.INTERNAL, 0.6);
        weights.put(ServiceExposure.ISOLATED, 0.4);
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
     * Severity factor for a signal with no CVSS, keyed by the feed's severity word (lower-cased). Two
     * vocabularies are shipped — NVD's {@code critical/high/medium/low} and Statuspage's
     * {@code critical/major/minor/none}, plus a few near-synonyms — and a deployment ingesting a feed
     * that speaks a third can add its words here rather than living with the neutral fallback. An
     * unrecognised word still falls back to {@code unknownSeverityFactor}: a label we cannot read must
     * not rank as harmless.
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
        labels.put("none", 0.1);
        labels.put("maintenance", 0.1);
        labels.put("informational", 0.1);
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

    /** The configured factor for a severity word (already lower-cased), or null when it is unrecognised. */
    Double severityLabelFactor(String label) {
        return label == null ? null : severityLabel.get(label);
    }
}
