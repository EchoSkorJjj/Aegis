package com.aegis.core.actions.upgrade;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.aegis.core.actions.evidence.DraftEvidence;
import com.aegis.core.actions.upgrade.UpgradeProposal.Edit;
import com.aegis.core.platform.common.SensitiveText;

/**
 * The title and Markdown body of a safe-upgrade pull request, written once when the proposal is drafted
 * so the approver reviews exactly what will be posted. A response's pull request is opened with its risk
 * ticket's key in front of the title and at the foot of the body ({@link #titled}, {@link #tracking}), so the
 * tracker links the two.
 *
 * <p>The facts (versions, the ranges checked, the score) are deterministic. The rationale, when the
 * ai-service supplied one, sits in its own quoted, labelled section. Everything a feed or a model wrote
 * is made inert before it lands in a repository: no {@code @}-mentions that would notify people, no
 * issue cross-references that would post backlinks, no remote images, no raw HTML.
 */
final class UpgradePullRequestText {

    static final int MAX_TITLE = 256;
    private static final int MAX_RATIONALE = 8_000;
    private static final int MAX_CITATIONS = 5;
    private static final int MAX_CITATION = 500;
    /** Keeps the whole body well inside the 60,000 characters a draft pull request may have. */
    private static final int MAX_EVIDENCE = 20_000;
    private static final int MAX_LISTED = 20;

    private UpgradePullRequestText() {
    }

    /**
     * What the body states: every edit, in order. {@code notCleared} and {@code followUps} may be empty; score
     * may be null. {@code followUps} are Markdown the editors wrote, each checklist item's text, with anything taken
     * from the repository in code spans.
     */
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
            List<Edit> edits,
            String matchConfidence,
            List<String> evidence,
            List<String> followUps) {

        Facts {
            edits = List.copyOf(edits);
            evidence = List.copyOf(evidence);
        }
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
        return bounded("Upgrade " + packageName + " from " + fromVersion + " to " + toVersion + " (" + vulnerabilityId + ")");
    }

    /**
     * One edit keeps its versions in the title. Several name the package and, when they share it, the version —
     * "Upgrade axios to 1.8.2 in 2 manifests" — or the packages when there are more than one.
     */
    static String title(List<Edit> edits, String vulnerabilityId) {
        if (edits.size() == 1) {
            Edit edit = edits.getFirst();
            return title(edit.packageName(), edit.fromVersion(), edit.toVersion(), vulnerabilityId);
        }
        List<String> packages = edits.stream().map(Edit::packageName).distinct().toList();
        Set<String> targets = edits.stream().map(Edit::toVersion).collect(Collectors.toCollection(LinkedHashSet::new));
        long manifests = edits.stream().map(Edit::manifestPath).distinct().count();
        String change;
        if (packages.size() == 1) {
            change = "Upgrade " + packages.getFirst() + (targets.size() == 1 ? " to " + targets.iterator().next() : "")
                    + (manifests > 1 ? " in " + manifests + " manifests" : "");
        } else {
            change = "Upgrade " + packages.getFirst() + (packages.size() == 2 ? " and " + packages.get(1)
                    : " and " + (packages.size() - 1) + " more packages");
        }
        return bounded(change + " (" + vulnerabilityId + ")");
    }

    /** The title a response's pull request opens with: its risk ticket's key first, once. */
    static String titled(String issueKey, String title) {
        String prefix = issueKey.replaceAll("\\p{Cntrl}", "") + ": ";
        return title.startsWith(prefix) ? title : bounded(prefix + title);
    }

    /** The body a response's pull request opens with: the reviewed body, then the risk ticket it tracks. */
    static String tracking(String issueKey, String body) {
        return body + "\nTracks risk ticket **" + inline(issueKey) + "** in the organization's issue tracker.\n";
    }

    static String body(Facts facts, Rationale rationale) {
        List<Edit> edits = facts.edits();
        boolean single = edits.size() == 1;
        StringBuilder body = new StringBuilder();
        body.append("## Safe upgrade proposed by Aegis\n\n");
        if (single) {
            Edit edit = edits.getFirst();
            body.append(edit.override() == null ? "Upgrades " : "Overrides ").append(code(edit.packageName()))
                    .append(" from **").append(inline(edit.fromVersion())).append("** to **")
                    .append(inline(edit.toVersion())).append("** in ").append(code(edit.manifestPath()))
                    .append(" to remediate **").append(inline(facts.vulnerabilityId())).append("** for the ")
                    .append(inline(facts.serviceName())).append(" service.")
                    .append(edit.override() == null ? "" : " " + pulledIn(edit)).append("\n\n");
        } else {
            body.append("Remediates **").append(inline(facts.vulnerabilityId())).append("** for the ")
                    .append(inline(facts.serviceName())).append(" service by upgrading every declaration below")
                    .append(edits.stream().anyMatch(edit -> edit.override() != null)
                            ? ", and overriding what the project pulls in without declaring it" : "")
                    .append(".\n\n")
                    .append("| Package | Manifest | From | To |\n|---|---|---|---|\n");
            for (Edit edit : edits) {
                body.append("| ").append(cellCode(edit.packageName())).append(edit.override() == null ? ""
                                : " (override" + (edit.override().via() == null ? ""
                                        : ", through " + cellCode(edit.override().via())) + ")")
                        .append(" | ").append(cellCode(edit.manifestPath()))
                        .append(" | ").append(cell(edit.fromVersion())).append(" | ").append(cell(edit.toVersion()))
                        .append(" |\n");
            }
            body.append('\n');
        }
        List<String> mechanisms = edits.stream().filter(edit -> edit.override() != null)
                .map(UpgradePullRequestText::mechanism).distinct().toList();
        if (!mechanisms.isEmpty()) {
            body.append("**How the override works:** no manifest declares the package, so Aegis pins its version for ")
                    .append("the whole project with ").append(and(mechanisms)).append(". Every copy of it in the ")
                    .append("dependency tree moves to the fixed version, even where what pulls it in asks for an older ")
                    .append("range: check that those packages still work with it. Once they require a fixed version ")
                    .append("themselves, the override can go.\n\n");
        }

        if (!facts.followUps().isEmpty()) {
            body.append("**Finish it before merging:** Aegis edits the manifests only, so do the first steps under ")
                    .append("*Before merging* on this branch.\n\n");
        }

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

        if (single) {
            body.append("**Why ").append(inline(edits.getFirst().toVersion()))
                    .append(":** it is the lowest version outside every ")
                    .append("published affected range Aegis holds for this package that has a fix.\n\n");
        } else {
            body.append("**Why these versions:** each is the lowest version outside every published affected range ")
                    .append("Aegis holds for its package that has a fix.\n\n");
        }
        if (!facts.notCleared().isEmpty()) {
            body.append("**Not cleared by this upgrade:** ").append(inline(list(facts.notCleared())))
                    .append(". No fixed version is published, the match is by name only, or the version cannot ")
                    .append("be compared. Review these separately.\n\n");
        }
        if ("POSSIBLE".equals(facts.matchConfidence())) {
            body.append("**The match is POSSIBLE, not confirmed:** check that this service really uses the ")
                    .append("affected package before merging.\n\n");
        }
        if (!facts.evidence().isEmpty()) {
            body.append("### Evidence at drafting\n\n");
            for (String line : evidence(facts.evidence())) {
                body.append("- ").append(line).append('\n');
            }
            body.append('\n');
        }

        body.append("### Before merging\n\n");
        for (String followUp : facts.followUps()) {
            body.append("- [ ] ").append(followUp).append('\n');
        }
        if (single) {
            Edit edit = edits.getFirst();
            body.append("- [ ] The build and tests pass with ").append(code(edit.packageName())).append(" ")
                    .append(inline(edit.toVersion())).append(".\n")
                    .append("- [ ] The release notes up to ").append(inline(edit.toVersion()))
                    .append(" contain no breaking change for this service.\n");
        } else {
            body.append("- [ ] The build and tests pass with the upgraded versions.\n")
                    .append("- [ ] The release notes up to each new version contain no breaking change for this ")
                    .append("service.\n");
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

    /** Why an override, not a bump: what pulls the package in, which is what the project declares. */
    private static String pulledIn(Edit edit) {
        return edit.override().via() == null
                ? "No manifest declares " + code(edit.packageName()) + "; the lockfile resolves it for the project, so "
                        + "Aegis pins it there."
                : code(edit.packageName()) + " comes in through " + code(edit.override().via())
                        + ", which is what the project declares, so Aegis pins it rather than editing a declaration.";
    }

    /** How an override is written, in the words of the tool that reads it. */
    private static String mechanism(Edit edit) {
        String manager = edit.override().packageManager();
        return switch (edit.ecosystem().trim().toLowerCase(Locale.ROOT)) {
            case "maven" -> "`dependencyManagement` in `pom.xml`";
            case "pypi", "pip" -> "a requirement of its own in `requirements.txt`";
            default -> "pnpm".equals(manager) ? "pnpm's `overrides` in `pnpm-workspace.yaml`"
                    : "yarn".equals(manager) ? "yarn's `resolutions` in `package.json`"
                    : "npm's `overrides` in `package.json`";
        };
    }

    /** Text safe inside Markdown prose: nothing that notifies, cross-links, embeds or renders HTML. */
    static String inert(String text) {
        return DraftEvidence.inert(text);
    }

    private static String inline(String text) {
        return inert(text).replace('\n', ' ');
    }

    /**
     * The evidence lines within {@value #MAX_EVIDENCE} characters. The dependency paths come first and the score
     * and source last, so a service reached by many paths keeps both ends and says how many lines are left out.
     */
    static List<String> evidence(List<String> lines) {
        List<String> items = lines.stream().map(UpgradePullRequestText::inline).toList();
        if (items.stream().mapToInt(String::length).sum() <= MAX_EVIDENCE) {
            return items;
        }
        int tail = items.size();
        int used = 0;
        while (tail > 0 && used + items.get(tail - 1).length() <= MAX_EVIDENCE / 2) {
            used += items.get(--tail).length();
        }
        int head = 0;
        while (head < tail && used + items.get(head).length() <= MAX_EVIDENCE) {
            used += items.get(head++).length();
        }
        List<String> kept = new ArrayList<>(items.subList(0, head));
        if (tail > head) {
            kept.add("… " + (tail - head) + " more lines of evidence left out to stay within GitHub's size limit");
        }
        kept.addAll(items.subList(tail, items.size()));
        return kept;
    }

    private static String cell(String text) {
        return inline(text).replace("|", "\\|");
    }

    /** A code span inside a table cell, where a pipe would end the cell. */
    private static String cellCode(String text) {
        return code(text).replace("|", "\\|");
    }

    /** A code span: Markdown processes no mentions, references or HTML inside one. */
    static String code(String text) {
        text = SensitiveText.redact(text);
        return "`" + (text == null ? "" : text.replace("`", "'").replaceAll("\\p{Cntrl}", " ")) + "`";
    }

    private static String quote(String text) {
        return inert(text).lines().map(line -> "> " + line).collect(Collectors.joining("\n"));
    }

    private static String label(String value) {
        return value == null ? "unknown" : inline(value.replace('_', '-').toLowerCase(Locale.ROOT));
    }

    /** "a", "a and b", "a, b and c". */
    static String and(List<String> items) {
        return items.size() <= 1 ? String.join("", items)
                : String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.getLast();
    }

    /** At most {@value #MAX_LISTED} ids, then how many more. */
    private static String list(List<String> ids) {
        return ids.size() <= MAX_LISTED
                ? String.join(", ", ids)
                : String.join(", ", ids.subList(0, MAX_LISTED)) + " and " + (ids.size() - MAX_LISTED) + " more";
    }

    private static String bounded(String text, int max) {
        text = SensitiveText.redact(text);
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** A title within GitHub's limit, on one line. */
    private static String bounded(String title) {
        title = SensitiveText.redact(title);
        return (title.length() <= MAX_TITLE ? title : title.substring(0, MAX_TITLE - 1) + "…")
                .replaceAll("\\p{Cntrl}", " ");
    }
}
