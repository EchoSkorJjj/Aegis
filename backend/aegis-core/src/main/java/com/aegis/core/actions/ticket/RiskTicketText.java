package com.aegis.core.actions.ticket;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.aegis.core.actions.evidence.DraftEvidence;
import com.aegis.core.actions.tracker.IssueTracker.Block;
import com.aegis.core.actions.tracker.IssueTracker.IssueDraft;
import com.aegis.core.actions.tracker.IssueTracker.Priority;
import com.aegis.core.actions.upgrade.UpgradeProposal;
import com.aegis.core.actions.upgrade.UpgradeProposalStatus;
import com.aegis.core.platform.common.SensitiveText;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.scoring.ScoringDtos.ScoredService;
import com.aegis.core.signals.ChangeSignal.SignalType;

/**
 * Everything deterministic about a risk ticket: its title, priority, due date, labels and evidence, the
 * plain fallback when the AI cannot draft, and its tracker-neutral and ServiceNow shapes. Pure functions.
 */
final class RiskTicketText {

    /** Jira's limit on an issue summary. */
    static final int MAX_TITLE = 255;
    /** ServiceNow's default {@code short_description} length. */
    static final int MAX_SHORT_DESCRIPTION = 160;
    private static final int MAX_QUOTED = 300;

    private RiskTicketText() {
    }

    /** The narrative fields — the AI's, with the passages it cites, or the fallback's, with none. */
    record Sections(String summary, String risk, String impact, List<String> actions, boolean aiDrafted,
                    List<String> citations) {
    }

    static String title(SignalType type, String changeTitle, String serviceName) {
        String title = type == SignalType.VENDOR_OUTAGE
                ? "Vendor outage affects " + serviceName + ": " + changeTitle
                : changeTitle + " affects " + serviceName;
        return cut(title, MAX_TITLE);
    }

    /** The impact tier decides how urgent the ticket is; a person can still change it in the tracker. */
    static Priority priority(ImpactTier tier) {
        return switch (tier) {
            case CRITICAL -> Priority.HIGHEST;
            case HIGH -> Priority.HIGH;
            case MEDIUM -> Priority.MEDIUM;
            case LOW -> Priority.LOW;
            case NEGLIGIBLE -> Priority.LOWEST;
        };
    }

    /** When the risk should be dealt with, counted from the day it was drafted; none for a negligible one. */
    static LocalDate dueDate(ImpactTier tier, LocalDate drafted) {
        return switch (tier) {
            case CRITICAL -> drafted.plusDays(7);
            case HIGH -> drafted.plusDays(30);
            case MEDIUM -> drafted.plusDays(90);
            case LOW -> drafted.plusDays(180);
            case NEGLIGIBLE -> null;
        };
    }

    /** Jira labels may not contain spaces; each is folded to letters, digits, dots, dashes and underscores. */
    static List<String> labels(SignalType type, String changeTitle, ImpactTier tier) {
        changeTitle = SensitiveText.redact(changeTitle);
        List<String> labels = new ArrayList<>(List.of("aegis",
                type == SignalType.VENDOR_OUTAGE ? "vendor-outage" : "vulnerability",
                "impact-" + tier.name().toLowerCase(Locale.ROOT)));
        if (type == SignalType.VULNERABILITY) {
            String id = changeTitle.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
            if (!id.isEmpty()) {
                labels.add(cut(id, 64));
            }
        }
        return labels;
    }

    /** Why the change reaches the service, one line per path, then how the score came out. */
    static List<String> evidence(ScoredService scored) {
        return DraftEvidence.service(scored);
    }

    /** What the ticket says when the AI could not draft it: only facts Aegis holds, plainly put. */
    static Sections fallback(SignalType type, String title, String changeSummary, ScoredService scored,
            String vendor) {
        String service = scored.service().name();
        String risk = changeSummary == null || changeSummary.isBlank()
                ? title + "."
                : cut(changeSummary.strip(), 1200);
        String impact = cut(scored.explanation() == null ? "" : scored.explanation().strip(), 1200);
        List<String> actions = type == SignalType.VENDOR_OUTAGE
                ? List.of("Confirm with " + (vendor == null ? "the vendor" : vendor) + " how the outage affects " + service + ".",
                        "Switch " + service + " to its fallback or continuity arrangement if it is needed.",
                        "Record what was done on this ticket.")
                : List.of("Confirm which of " + service + "'s components listed below run an affected version.",
                        "Upgrade or mitigate each affected component.",
                        "Record what was done on this ticket.");
        return new Sections(cut(title, 300), risk, impact.isEmpty() ? risk : impact, actions, false, List.of());
    }

    /**
     * The ticket as the tracker will show it. AI text is quoted as plain text, never as markup.
     * {@code pullRequest} is the upgrade pull request that goes with it, if any: the tracker is told what it
     * will change and that it carries this ticket's key.
     */
    static IssueDraft issue(RiskTicket ticket, String serviceName, UpgradeProposal pullRequest) {
        List<Block> blocks = new ArrayList<>();
        blocks.add(Block.paragraph(ticket.summary()));
        blocks.add(Block.heading("Risk"));
        blocks.add(Block.paragraph(ticket.risk()));
        blocks.add(Block.heading("Impact"));
        blocks.add(Block.paragraph(ticket.impact()));
        blocks.add(Block.heading("Recommended actions"));
        blocks.add(Block.bullets(ticket.actions()));
        if (pullRequest != null && pullRequest.status() != UpgradeProposalStatus.DISMISSED) {
            blocks.add(Block.heading("Upgrade pull request"));
            blocks.add(Block.paragraph("Aegis opens it as a draft with this ticket's key in its title, upgrading:"));
            blocks.add(Block.bullets(pullRequest.edits().stream()
                    .map(edit -> edit.packageName() + " " + edit.fromVersion() + " to " + edit.toVersion() + " in "
                            + edit.manifestPath())
                    .toList()));
        }
        blocks.add(Block.heading("Evidence"));
        blocks.add(Block.bullets(ticket.evidence()));
        if (!ticket.citations().isEmpty()) {
            blocks.add(Block.heading("Sources the draft quotes"));
            blocks.add(Block.bullets(ticket.citations().stream().map(quote -> cut(quote.strip(), MAX_QUOTED)).toList()));
        }
        blocks.add(Block.paragraph("Service: " + (serviceName == null ? "a deleted service" : serviceName)
                + ". Drafted by Aegis" + (ticket.aiDrafted() ? " (the summary, risk, impact and actions are an "
                + "AI draft a person reviewed)" : "") + " and approved there by a second person."));
        return new IssueDraft(ticket.title(), blocks, ticket.priority(), ticket.dueDate(), ticket.labels());
    }

    /**
     * The ticket as a ServiceNow {@code incident}, to paste into the Table API or a form: impact and urgency
     * (1 high – 3 low) from the tier, the service as the configuration item, the description as plain text.
     */
    static Map<String, Object> serviceNow(RiskTicket ticket, String serviceName, UpgradeProposal pullRequest) {
        int[] impactUrgency = switch (ticket.tier()) {
            case CRITICAL -> new int[]{1, 1};
            case HIGH -> new int[]{1, 2};
            case MEDIUM -> new int[]{2, 2};
            case LOW -> new int[]{3, 2};
            case NEGLIGIBLE -> new int[]{3, 3};
        };
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("short_description", cut(ticket.title(), MAX_SHORT_DESCRIPTION));
        fields.put("description", plainText(issue(ticket, serviceName, pullRequest).description()));
        fields.put("impact", Integer.toString(impactUrgency[0]));
        fields.put("urgency", Integer.toString(impactUrgency[1]));
        fields.put("cmdb_ci", serviceName == null ? "" : SensitiveText.redact(serviceName));
        if (ticket.dueDate() != null) {
            fields.put("due_date", ticket.dueDate() + " 00:00:00");
        }
        return fields;
    }

    static String plainText(List<Block> blocks) {
        StringBuilder text = new StringBuilder();
        for (Block block : blocks) {
            switch (block.kind()) {
                case HEADING -> text.append('\n').append(block.text()).append('\n');
                case PARAGRAPH -> text.append(block.text()).append('\n');
                case BULLETS -> block.items().forEach(item -> text.append("- ").append(item).append('\n'));
            }
        }
        return text.toString().strip();
    }

    static String cut(String text, int max) {
        text = SensitiveText.redact(text);
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
