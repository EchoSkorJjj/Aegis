package com.aegis.core.scoring;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
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
 */
@ConfigurationProperties("aegis.scoring")
public record ScoringProperties(
        Map<BusinessCriticality, Double> criticality,
        Map<ServiceExposure, Double> exposure,
        Map<MatchConfidence, Double> confidence,
        Double unknownSeverityFactor,
        Tiers tiers,
        Double materialityThreshold,
        Rollup rollup) {

    // Every scalar is boxed on purpose. An unset property binds a primitive to 0, which is
    // indistinguishable from a deliberate 0 — and for materialityThreshold a silent 0 would mark
    // every result material, i.e. turn the noise control off without anyone configuring that. Null
    // means "not configured"; 0 keeps meaning 0.
    public ScoringProperties {
        criticality = merged(defaultCriticality(), criticality, BusinessCriticality.class);
        exposure = merged(defaultExposure(), exposure, ServiceExposure.class);
        confidence = merged(defaultConfidence(), confidence, MatchConfidence.class);
        if (unknownSeverityFactor == null || unknownSeverityFactor <= 0) {
            unknownSeverityFactor = 0.5;
        }
        if (tiers == null) {
            tiers = new Tiers(null, null, null, null);
        }
        if (materialityThreshold == null || materialityThreshold < 0) {
            materialityThreshold = 10.0;
        }
        if (rollup == null) {
            rollup = new Rollup(null, null);
        }
    }

    /** The shipped weighting — what an empty {@code aegis.scoring} block binds to. */
    public static ScoringProperties defaults() {
        return new ScoringProperties(null, null, null, null, null, null, null);
    }

    /**
     * An override names only the entries it wants to change, so a deployment that cares about one
     * tier does not have to restate the whole table (and cannot silently drop an enum constant added
     * by a later story).
     */
    private static <K extends Enum<K>> Map<K, Double> merged(
            Map<K, Double> defaults, Map<K, Double> overrides, Class<K> type) {
        if (overrides == null || overrides.isEmpty()) {
            return defaults;
        }
        Map<K, Double> merged = new EnumMap<>(type);
        merged.putAll(defaults);
        overrides.forEach((key, value) -> {
            if (key != null && value != null) {
                merged.put(key, value);
            }
        });
        return Map.copyOf(merged);
    }

    /**
     * Lower bounds of each impact tier on the 0-100 scale, highest first. A score at or above
     * {@code critical} is CRITICAL, at or above {@code high} is HIGH, and so on; anything below
     * {@code low} is NEGLIGIBLE.
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

    double criticalityFactor(BusinessCriticality value) {
        return value == null ? 0.5 : criticality.getOrDefault(value, 0.5);
    }

    double exposureFactor(ServiceExposure value) {
        return exposure.getOrDefault(value == null ? BusinessService.DEFAULT_EXPOSURE : value, 0.6);
    }

    double confidenceFactor(MatchConfidence value) {
        return value == null ? 1.0 : confidence.getOrDefault(value, 1.0);
    }
}
