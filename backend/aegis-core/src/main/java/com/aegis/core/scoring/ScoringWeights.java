package com.aegis.core.scoring;

import java.util.EnumMap;
import java.util.Map;

import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.ServiceExposure;

/**
 * The numbers the impact model multiplies — every judgement call in scoring, in one place.
 *
 * <p>They are data rather than constants scattered through {@link ImpactScorer} because the whole
 * point of a deterministic score is that a reviewer can see why a service ranked where it did and
 * disagree with the weighting rather than the arithmetic.
 *
 * <p>Each factor is a multiplier in {@code [0,1]}, so the product is too and the score is a plain
 * percentage of "the worst this could be". Defaults are relative, not absolute: an internet-facing
 * service is worth about 2.5x an isolated one, a CRITICAL tier about 4x a LOW one.
 */
public record ScoringWeights(
        Map<BusinessCriticality, Double> criticality,
        Map<ServiceExposure, Double> exposure,
        double unknownSeverityFactor,
        Tiers tiers,
        double materialityThreshold) {

    public ScoringWeights {
        criticality = criticality == null || criticality.isEmpty()
                ? defaultCriticality()
                : Map.copyOf(criticality);
        exposure = exposure == null || exposure.isEmpty() ? defaultExposure() : Map.copyOf(exposure);
        if (unknownSeverityFactor <= 0) {
            unknownSeverityFactor = 0.5;
        }
        if (tiers == null) {
            tiers = new Tiers(0, 0, 0, 0);
        }
        if (materialityThreshold < 0) {
            materialityThreshold = 10.0;
        }
    }

    /** The shipped weighting. */
    public static ScoringWeights defaults() {
        return new ScoringWeights(defaultCriticality(), defaultExposure(), 0.5, new Tiers(0, 0, 0, 0), 10.0);
    }

    /**
     * Lower bounds of each impact tier on the 0-100 scale, highest first. A score at or above
     * {@code critical} is CRITICAL, at or above {@code high} is HIGH, and so on; anything below
     * {@code low} is NEGLIGIBLE.
     */
    public record Tiers(double critical, double high, double medium, double low) {

        public Tiers {
            if (critical <= 0) {
                critical = 75.0;
            }
            if (high <= 0) {
                high = 50.0;
            }
            if (medium <= 0) {
                medium = 25.0;
            }
            if (low <= 0) {
                low = 10.0;
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

    double criticalityFactor(BusinessCriticality value) {
        return value == null ? 0.5 : criticality.getOrDefault(value, 0.5);
    }

    double exposureFactor(ServiceExposure value) {
        return exposure.getOrDefault(value == null ? BusinessService.DEFAULT_EXPOSURE : value, 0.6);
    }
}
