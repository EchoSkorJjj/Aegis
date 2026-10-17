package com.aegis.core.actions.evidence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.aegis.core.actions.assess.ReachabilityAssessment;
import com.aegis.core.actions.assess.ReachabilityAssessments;
import com.aegis.core.matching.MatchingDtos.ChangeRef;
import com.aegis.core.matching.MatchingDtos.EvidenceChain;
import com.aegis.core.matching.MatchingDtos.OwnerRef;
import com.aegis.core.platform.common.SensitiveText;
import com.aegis.core.scoring.ScoringDtos.ScoredService;
import com.aegis.core.signals.ChangeSignal;

/**
 * Evidence captured when a response is drafted, shared by tickets, upgrade PRs and memos (E8 story 7).
 * These are the source and deterministic scoring inputs used then, not a later recomputation against
 * a changed estate. Callers persist the text in their existing evidence list or reviewed draft body.
 */
public final class DraftEvidence {

    private static final int MAX_EXCERPT = 4_000;

    private DraftEvidence() {
    }

    public static List<String> source(ChangeRef change, ChangeSignal signal) {
        return source(change, signal == null ? null : signal.summary(),
                signal == null ? null : signal.occurredAt(), signal == null ? null : signal.url());
    }

    public static List<String> source(ChangeRef change, String summary, Instant occurredAt, String url) {
        List<String> lines = new ArrayList<>();
        lines.add("Change: " + change.signalType() + " "
                + (blank(change.title()) ? change.externalId() : change.title()) + " (" + change.id() + ").");
        lines.add("Source: " + change.sourceId() + " / " + change.externalId() + ".");
        // Aegis records no link for an advisory, only for vendor incidents and news; the source still publishes one.
        lines.add("Source URL: " + (blank(url) ? "not recorded" : url.strip()));
        lines.add("Source time: " + (occurredAt == null ? "not published" : occurredAt.toString()));
        String excerpt = blank(summary) ? "not published" : SensitiveText.redact(summary.strip());
        if (excerpt.length() > MAX_EXCERPT) {
            excerpt = excerpt.substring(0, MAX_EXCERPT) + "… [excerpt truncated]";
        }
        lines.add("Source excerpt: " + excerpt);
        return SensitiveText.redact(lines);
    }

    /** Matching paths, affected service and owners, and every factor needed to reproduce its score. */
    public static List<String> service(ScoredService scored) {
        List<String> lines = new ArrayList<>();
        for (EvidenceChain chain : scored.evidence()) {
            if (!blank(chain.explanation())) {
                lines.add(chain.explanation().strip());
            }
        }
        lines.add(String.format(Locale.ROOT, "Impact score %.1f (%s%s). %s", scored.score(), scored.tier(),
                scored.material() ? ", material" : "", blank(scored.explanation()) ? "" : scored.explanation().strip())
                .strip());
        lines.add("Affected service: " + scored.service().name() + " (" + scored.service().id() + "), "
                + scored.service().criticality() + " criticality, " + scored.service().exposure() + ".");
        lines.add("Service owners: " + (scored.owners().isEmpty() ? "none assigned"
                : scored.owners().size() + " assigned (personal details omitted)") + ".");
        lines.add("Match confidence: " + scored.confidence() + ".");
        if (scored.reachability() != null) {
            // Whether the code reaches it, and why — never the files and lines, which stay in Aegis.
            var reachability = scored.reachability();
            lines.add("Code reachability: " + reachability.status() + ". " + (reachability.reason() != null
                    ? reachability.reason()
                    : "The service's code imports it in " + reachability.usage().siteCount() + " place"
                            + (reachability.usage().siteCount() == 1 ? "" : "s")) + ".");
        }
        var breakdown = scored.breakdown();
        if (breakdown != null) {
            lines.add("Score calculation: severity " + number(breakdown.severityFactor())
                    + " x exposure " + number(breakdown.exposureFactor())
                    + (breakdown.reachabilityApplies() ? " x reachability " + number(breakdown.reachabilityFactor()) : "")
                    + " x criticality " + number(breakdown.criticalityFactor())
                    + " x confidence " + number(breakdown.confidenceFactor()) + " x 100 = "
                    + String.format(Locale.ROOT, "%.1f", scored.score()) + " (rounded to one decimal)."
                    + (breakdown.exposureApplies() ? "" : " Exposure discount does not apply to this change."));
        }
        if (scored.factors() != null) {
            scored.factors().forEach(factor -> lines.add("Score factor " + factor.factor() + ": " + factor.input()
                    + "; weight " + number(factor.weight())
                    + (factor.limiting() ? "; limiting factor" : "")
                    + (factor.setting() == null ? "" : "; setting " + factor.setting()) + "."));
        }
        // Matching explanations include display names for the authorized impact view. Drafts
        // and AI context retain only the owner count; ordinary names are not regex-detectable PII.
        String ownerNames = scored.owners().stream().map(OwnerRef::name).filter(name -> !blank(name))
                .map(String::strip).distinct().sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote).collect(Collectors.joining("|"));
        List<String> protectedLines = lines;
        if (!ownerNames.isEmpty()) {
            Pattern names = Pattern.compile("(?<![\\p{L}\\p{N}_])(?:" + ownerNames + ")(?![\\p{L}\\p{N}_])");
            protectedLines = lines.stream().map(line -> names.matcher(line).replaceAll(SensitiveText.REDACTED)).toList();
        }
        return SensitiveText.redact(protectedLines);
    }

    /**
     * The AI's suggestion on whether the service's code uses the vulnerable part of a package (E11-5), one line per
     * package — its answer, rationale and what a reviewer should check, or why there is none — and never the files or
     * lines it read: drafts go to the AI provider and to the issue tracker.
     */
    private static final Map<ReachabilityAssessment.Verdict, String> VERDICT_WORDS = Map.of(
            ReachabilityAssessment.Verdict.CALLED, "the code uses the vulnerable part",
            ReachabilityAssessment.Verdict.NOT_CALLED, "the code doesn't use the vulnerable part",
            ReachabilityAssessment.Verdict.CANT_TELL, "can't tell whether the code uses the vulnerable part");

    public static List<String> assessments(List<ReachabilityAssessments.Item> items) {
        List<String> lines = new ArrayList<>();
        for (ReachabilityAssessments.Item item : items) {
            String subject = "AI reachability suggestion for " + item.packageName() + ": ";
            String checks = item.checks().isEmpty() ? ""
                    : " To check: " + sentence(String.join("; ", item.checks().stream().map(String::strip)
                            .map(check -> check.replaceAll("[.;]+$", "")).toList()));
            lines.add(item.status() == ReachabilityAssessments.Status.ASSESSED
                    ? subject + VERDICT_WORDS.get(item.verdict()) + ". " + sentence(item.rationale()) + checks
                            + " A suggestion: it doesn't change the score."
                    : subject + "not assessed. " + sentence(item.reason()));
        }
        return SensitiveText.redact(lines);
    }

    private static String sentence(String text) {
        String stripped = text == null ? "" : text.strip();
        return stripped.isEmpty() || stripped.matches(".*[.!?…]$") ? stripped : stripped + ".";
    }

    /** A quoted source passage must remain text when the memo is downloaded as Markdown. */
    public static String quote(String text) {
        return inert(text).lines().map(line -> "> " + line).collect(Collectors.joining("\n"));
    }

    /** Feed text cannot notify people, embed images or render HTML when attached as Markdown evidence. */
    public static String inert(String text) {
        if (text == null) {
            return "";
        }
        return SensitiveText.redact(text).replace("\r", "").replace("<", "&lt;").replace("@", "@\u200b")
                .replaceAll("#(\\d)", "#\u200b$1").replace("![", "!\u200b[");
    }

    private static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
