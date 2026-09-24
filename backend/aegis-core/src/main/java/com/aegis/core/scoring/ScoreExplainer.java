package com.aegis.core.scoring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.scoring.ScoringDtos.ScoreBreakdown;
import com.aegis.core.scoring.ScoringDtos.ScoreFactor;
import com.aegis.core.scoring.ScoringDtos.ScoreFactorKind;
import com.aegis.core.scoring.ScoringDtos.SeverityInput;
import com.aegis.core.scoring.ScoringDtos.SeveritySource;

/**
 * Turns a score's arithmetic into its reasons — the "why this number" half of the impact model.
 * {@link ImpactScorer} multiplies the terms; this names each one, says what it was read from and
 * which {@code aegis.scoring.*} property sets it, and works out which is holding the score down.
 *
 * <p>"Holding the score down" is measured as <em>headroom</em>: the points a factor alone would add
 * if it were at its maximum, the others staying where they are. In a product that is the per-factor
 * measure that stays honest — an additive "share of the score" would have to pretend the terms are
 * independent — and it reads the way an argument about a score actually goes: "if this service were
 * internet-facing it would be 40 points higher".
 *
 * <p>Headroom is computed by re-scoring through {@link ImpactScorer} with that one factor at 1.0, not
 * from the raw weights, so it inherits the scorer's rounding: {@code score + headroom} is exactly the
 * score a reader would see if they made that change.
 *
 * <p>Pure, like the scorer: the same breakdown always explains the same way, and nothing here reads
 * advisory prose or calls a model.
 */
final class ScoreExplainer {

    private static final String SETTINGS = "aegis.scoring.";

    /** The exposure term's input when it does not apply — see {@link ImpactScorer#breakdown}. */
    static final String NOT_APPLIED = "not applied to outages";

    private final ImpactScorer scorer;

    ScoreExplainer(ImpactScorer scorer) {
        this.scorer = scorer;
    }

    /** Every term of the score, in model order, with its headroom and the limiting one(s) flagged. */
    List<ScoreFactor> factors(SeverityInput severity, ScoreBreakdown breakdown) {
        ServiceExposure exposure = breakdown.exposure() == null
                ? BusinessService.DEFAULT_EXPOSURE
                : breakdown.exposure();
        List<Term> terms = List.of(
                new Term(ScoreFactorKind.SEVERITY, describeSeverity(severity),
                        breakdown.severityFactor(), severitySetting(severity)),
                breakdown.exposureApplies()
                        ? new Term(ScoreFactorKind.EXPOSURE,
                                breakdown.exposure() == null ? exposure + " (default)" : exposure.name(),
                                breakdown.exposureFactor(), setting("exposure", exposure))
                        // An outage is not an attack, so no exposure setting can move this term.
                        : new Term(ScoreFactorKind.EXPOSURE, NOT_APPLIED, breakdown.exposureFactor(), null),
                new Term(ScoreFactorKind.CRITICALITY,
                        breakdown.criticality() == null ? "unset" : breakdown.criticality().name(),
                        breakdown.criticalityFactor(), setting("criticality", breakdown.criticality())),
                new Term(ScoreFactorKind.CONFIDENCE,
                        breakdown.confidence() == null ? "unset" : breakdown.confidence().name(),
                        breakdown.confidenceFactor(), setting("confidence", breakdown.confidence())));

        double score = scorer.score(breakdown);
        double[] headrooms = new double[terms.size()];
        double most = 0;
        for (int index = 0; index < terms.size(); index++) {
            // Subtracting two one-decimal scores can leave float noise (29.400000000000006), so re-round.
            headrooms[index] = round(scorer.score(maximised(breakdown, terms.get(index).kind())) - score);
            most = Math.max(most, headrooms[index]);
        }

        List<ScoreFactor> factors = new ArrayList<>();
        for (int index = 0; index < terms.size(); index++) {
            Term term = terms.get(index);
            factors.add(new ScoreFactor(
                    term.kind(), term.input(), term.weight(), headrooms[index],
                    most > 0 && headrooms[index] == most, term.setting()));
        }
        return List.copyOf(factors);
    }

    /**
     * The one-sentence form: the multiplication as it was done, then — when anything is holding the
     * score below the worst case — which factor holds it down most and by how much. A deployment's
     * confidence discount is only spelled out when one is configured, so the default sentence stays
     * the three-factor model it is described as.
     */
    String explain(
            String changeTitle,
            String serviceName,
            double score,
            ImpactTier tier,
            ScoreBreakdown breakdown,
            List<ScoreFactor> factors) {
        StringBuilder sentence = new StringBuilder(
                "%s scores %s (%s) for %s: severity %s (%s) x exposure %s (%s) x criticality %s (%s)".formatted(
                        changeTitle,
                        format(score),
                        tier,
                        serviceName,
                        weight(breakdown.severityFactor()),
                        factor(factors, ScoreFactorKind.SEVERITY).input(),
                        weight(breakdown.exposureFactor()),
                        factor(factors, ScoreFactorKind.EXPOSURE).input(),
                        weight(breakdown.criticalityFactor()),
                        factor(factors, ScoreFactorKind.CRITICALITY).input()));
        if (breakdown.confidenceFactor() != 1.0) {
            sentence.append(" x confidence %s (%s)".formatted(
                    weight(breakdown.confidenceFactor()), factor(factors, ScoreFactorKind.CONFIDENCE).input()));
        }

        List<ScoreFactor> limiting = factors.stream().filter(ScoreFactor::limiting).toList();
        if (!limiting.isEmpty()) {
            String names = limiting.stream()
                    .map(limit -> "%s (%s)".formatted(limit.factor().name().toLowerCase(Locale.ROOT), limit.input()))
                    .collect(Collectors.joining(" and "));
            sentence.append("; %s %s it down most, costing %s points%s".formatted(
                    names,
                    limiting.size() == 1 ? "holds" : "hold",
                    format(limiting.getFirst().headroom()),
                    limiting.size() == 1 ? "" : " each"));
        }
        return sentence.toString();
    }

    /** The same breakdown with one factor at its maximum and every other term untouched. */
    private static ScoreBreakdown maximised(ScoreBreakdown b, ScoreFactorKind kind) {
        return new ScoreBreakdown(
                kind == ScoreFactorKind.SEVERITY ? 1.0 : b.severityFactor(),
                b.exposure(),
                kind == ScoreFactorKind.EXPOSURE ? 1.0 : b.exposureFactor(),
                b.exposureApplies(),
                b.criticality(),
                kind == ScoreFactorKind.CRITICALITY ? 1.0 : b.criticalityFactor(),
                b.confidence(),
                kind == ScoreFactorKind.CONFIDENCE ? 1.0 : b.confidenceFactor());
    }

    private static ScoreFactor factor(List<ScoreFactor> factors, ScoreFactorKind kind) {
        return factors.stream()
                .filter(candidate -> candidate.factor() == kind)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No " + kind + " factor to explain"));
    }

    /** Where the severity multiplier came from: a feed's published number, or a configured table entry. */
    private static String describeSeverity(SeverityInput severity) {
        if (severity.source() == SeveritySource.CVSS) {
            String version = severity.cvssVersion() == null ? "CVSS" : "CVSS " + severity.cvssVersion();
            return version + " base " + trim(severity.cvssBaseScore());
        }
        if (severity.source() == SeveritySource.SEVERITY_LABEL) {
            return "severity " + severity.label();
        }
        return severity.label() == null
                ? "no published severity"
                : "unrecognised severity '" + severity.label() + "'";
    }

    /**
     * A CVSS base score is published by the feed, so there is no setting behind it; a severity word is
     * a row of the configurable label table; anything else fell back to the neutral unknown factor.
     */
    private static String severitySetting(SeverityInput severity) {
        return switch (severity.source()) {
            case CVSS -> null;
            case SEVERITY_LABEL -> SETTINGS + "severity-label." + severity.label().trim().toLowerCase(Locale.ROOT);
            case NONE -> SETTINGS + "unknown-severity-factor";
        };
    }

    /** The property key as it is written in {@code application.yml}, e.g. {@code aegis.scoring.exposure.internet-facing}. */
    private static String setting(String table, Enum<?> key) {
        return key == null
                ? null
                : SETTINGS + table + "." + key.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String trim(BigDecimal value) {
        return value == null ? "n/a" : value.stripTrailingZeros().toPlainString();
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String weight(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private record Term(ScoreFactorKind kind, String input, double weight, String setting) {
    }
}
