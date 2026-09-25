package com.aegis.core.actions.upgrade;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The title and Markdown body of a safe-upgrade pull request, written once when the proposal is drafted
 * so the approver reviews exactly what will be posted.
 *
 * <p>The facts (versions, the ranges checked, the score) are deterministic. The rationale, when the
 * ai-service supplied one, sits in its own quoted, labelled section. Everything a feed or a model wrote
 * is made inert before it lands in a repository: no {@code @}-mentions that would notify people, no
 * issue cross-references that would post backlinks, no remote images, no raw HTML.
 */
final class UpgradePullRequestText {

    static final int MAX_TITLE = 256;
    private static final int MAX_EXPLANATION = 2_000;
    private static final int MAX_RATIONALE = 8_000;
    private static final int MAX_CITATIONS = 5;
    private static final int MAX_CITATION = 500;
    private static final int MAX_LISTED = 20;

    private UpgradePullRequestText() {
    }

    /** What the body states. {@code notCleared} and {@code followUps} may be empty; score may be null. */
    record Facts(
            UUID proposalId,
            String vulnerabilityId,
            List<String> checked,
            List<String> notCleared,
            String serviceName,
            String criticality,
            String exposure,
            Double score,
            String tier,
            String packageName,
            String fromVersion,
            String toVersion,
            String manifestPath,
            String matchConfidence,
            String evidence,
            List<String> followUps) {
    }

    /** The ai-service's draft, or why there is none. */
    record Rationale(String content, List<String> citations, String unavailableReason) {

        static Rationale drafted(String content, List<String> citations) {
            return new Rationale(content, citations == null ? List.of() : citations, null);
        }

        static Rationale unavailable(String reason) {
            return new Rationale(null, List.of(), reason);
        }
    }

    static String title(String packageName, String fromVersion, String toVersion, String vulnerabilityId) {
        String title = "Upgrade " + packageName + " from " + fromVersion + " to " + toVersion + " (" + vulnerabilityId + ")";
        return (title.length() <= MAX_TITLE ? title : title.substring(0, MAX_TITLE - 1) + "…")
                .replaceAll("\\p{Cntrl}", " ");
    }

    static String body(Facts facts, Rationale rationale) {
        StringBuilder body = new StringBuilder();
        body.append("## Safe upgrade proposed by Aegis\n\n")
                .append("Upgrades ").append(code(facts.packageName()))
                .append(" from **").append(inline(facts.fromVersion())).append("** to **")
                .append(inline(facts.toVersion())).append("** in ").append(code(facts.manifestPath()))
                .append(" to remediate **").append(inline(facts.vulnerabilityId())).append("** for the ")
                .append(inline(facts.serviceName())).append(" service.\n\n");

        body.append("| | |\n|---|---|\n")
                .append("| Service | ").append(cell(facts.serviceName())).append(" (")
                .append(label(facts.criticality())).append(" criticality, ").append(label(facts.exposure()))
                .append(") |\n");
        if (facts.score() != null) {
            body.append("| Impact score | ").append(String.format(Locale.ROOT, "%.1f", facts.score()))
                    .append(" (").append(label(facts.tier())).append(") |\n");
        }
        body.append("| Match confidence | ").append(label(facts.matchConfidence())).append(" |\n")
                .append("| Checked against | ").append(cell(list(facts.checked()))).append(" |\n\n");

        body.append("**Why ").append(inline(facts.toVersion())).append(":** it is the lowest version outside every ")
                .append("published affected range Aegis holds for this package that has a fix.\n\n");
        if (!facts.notCleared().isEmpty()) {
            body.append("**Not cleared by this upgrade:** ").append(inline(list(facts.notCleared())))
                    .append(". No fixed version is published, the match is by name only, or the version cannot ")
                    .append("be compared. Review these separately.\n\n");
        }
        if ("POSSIBLE".equals(facts.matchConfidence())) {
            body.append("**The match is POSSIBLE, not confirmed:** check that this service really uses the ")
                    .append("affected package before merging.\n\n");
        }
        if (facts.evidence() != null && !facts.evidence().isBlank()) {
            body.append("**Evidence:** ").append(inline(bounded(facts.evidence(), MAX_EXPLANATION))).append("\n\n");
        }

        body.append("### Before merging\n\n")
                .append("- [ ] The build and tests pass with ").append(code(facts.packageName())).append(" ")
                .append(inline(facts.toVersion())).append(".\n")
                .append("- [ ] The release notes up to ").append(inline(facts.toVersion()))
                .append(" contain no breaking change for this service.\n");
        for (String followUp : facts.followUps()) {
            body.append("- [ ] ").append(followUp).append('\n');
        }
        body.append('\n');

        body.append("### Rationale drafted by AI (unverified)\n\n");
        if (rationale.content() != null && !rationale.content().isBlank()) {
            body.append(quote(bounded(rationale.content(), MAX_RATIONALE))).append("\n\n");
            List<String> citations = rationale.citations().stream().limit(MAX_CITATIONS).toList();
            if (!citations.isEmpty()) {
                body.append("Sources it cited, quoted from the advisory and context Aegis supplied:\n\n");
                for (String citation : citations) {
                    body.append(quote(bounded(citation, MAX_CITATION))).append("\n\n");
                }
            }
        } else {
            body.append("_No AI rationale: ").append(inline(rationale.unavailableReason())).append("._\n\n");
        }

        body.append("---\n")
                .append("Opened as a **draft** by Aegis after a person approved proposal `")
                .append(facts.proposalId()).append("` in Aegis. Aegis never merges: a maintainer reviews and merges.\n");
        return body.toString();
    }

    /** Text safe inside Markdown prose: nothing that notifies, cross-links, embeds or renders HTML. */
    static String inert(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r", "")
                .replace("<", "&lt;")
                .replace("@", "@​")
                .replaceAll("#(\\d)", "#​$1")
                .replace("![", "!​[");
    }

    private static String inline(String text) {
        return inert(text).replace('\n', ' ');
    }

    private static String cell(String text) {
        return inline(text).replace("|", "\\|");
    }

    /** A code span: Markdown processes no mentions, references or HTML inside one. */
    private static String code(String text) {
        return "`" + (text == null ? "" : text.replace("`", "'").replaceAll("\\p{Cntrl}", " ")) + "`";
    }

    private static String quote(String text) {
        return inert(text).lines().map(line -> "> " + line).collect(Collectors.joining("\n"));
    }

    private static String label(String value) {
        return value == null ? "unknown" : inline(value.replace('_', '-').toLowerCase(Locale.ROOT));
    }

    /** At most {@value #MAX_LISTED} ids, then how many more. */
    private static String list(List<String> ids) {
        return ids.size() <= MAX_LISTED
                ? String.join(", ", ids)
                : String.join(", ", ids.subList(0, MAX_LISTED)) + " and " + (ids.size() - MAX_LISTED) + " more";
    }

    private static String bounded(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
