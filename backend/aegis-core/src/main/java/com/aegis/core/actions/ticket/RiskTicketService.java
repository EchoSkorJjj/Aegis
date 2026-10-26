package com.aegis.core.actions.ticket;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.aegis.core.actions.assess.ReachabilityAssessments;
import com.aegis.core.actions.evidence.DraftEvidence;
import com.aegis.core.actions.ticket.RiskTicketText.Sections;
import com.aegis.core.actions.tracker.IssueTracker;
import com.aegis.core.actions.upgrade.UpgradePlan;
import com.aegis.core.actions.upgrade.UpgradeProposal;
import com.aegis.core.actions.upgrade.UpgradeProposalService;
import com.aegis.core.actions.upgrade.UpgradeProposalStatus;
import com.aegis.core.ai.AiDrafter;
import com.aegis.core.ai.AiDtos;
import com.aegis.core.audit.DecisionRecord;
import com.aegis.core.audit.DecisionRecord.Action;
import com.aegis.core.audit.DecisionRecord.Subject;
import com.aegis.core.audit.DecisionTrail;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.matching.MatchingDtos.EvidenceChain;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.common.SensitiveText;
import com.aegis.core.scoring.ScoringDtos.ChangeImpactResponse;
import com.aegis.core.scoring.ScoringDtos.ScoredService;
import com.aegis.core.scoring.ScoringService;
import com.aegis.core.signals.ChangeSignal;
import com.aegis.core.signals.ChangeSignal.SignalType;
import com.aegis.core.signals.ChangeSignalStore;
import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Responses (E8 story 5): the risk ticket for one change's impact on one service — filed in the organization's
 * issue tracker — and, for a vulnerability, the one upgrade pull request that fixes it, decided together.
 *
 * <p><b>Propose</b> drafts both. The ticket's title, priority, due date, labels and evidence come from the impact
 * score (deterministic), and its summary, risk, impact and actions from the AI — or a plain fallback of Aegis's
 * own facts when the AI is unavailable, which never blocks drafting. The pull request edits every manifest that
 * declares an affected package ({@link UpgradeProposalService#plan}); what can't be automated is recorded on the
 * ticket instead. Nothing leaves Aegis.
 *
 * <p><b>Approve</b> is the trust boundary: it files the ticket ({@link IssueTracker}), then opens the pull
 * request with the ticket's key in its title. A pull request changes a repository, so the approver is never its
 * drafter; a response with no pull request only records a risk, so its drafter may file it. Each part can then
 * be retried on its own. <b>Dismiss</b> declines the whole response with a reason; leaving the pull request out
 * alone is dismissing just that ({@link UpgradeProposalService#dismiss}).
 *
 * <p>Before deciding, a reviewer may <b>edit</b> the drafted narrative and <b>override</b> Aegis's priority and due
 * date, with a reason (E9 story 3), and withdraw that override; both stay on record. Every decision may name the
 * version the reviewer saw, so someone deciding on a response that has changed since — edited, overridden or
 * decided by someone else — gets a conflict rather than a decision about something they never read.
 *
 * <p>Every step — drafted, edited, overridden or withdrawn, approved, filed or failed, dismissed — is recorded in the
 * {@link DecisionTrail} with the ticket as it then stood, in the same transaction as the step (E10 story 1).
 */
@Service
public class RiskTicketService {

    private static final Logger log = LoggerFactory.getLogger(RiskTicketService.class);

    /** An approval interrupted mid-way (a crash while filing) may be retried after this long. */
    static final Duration STALE_FILING = Duration.ofMinutes(10);
    private static final int MAX_CHANGE_CONTEXT = 4_000;
    private static final int MAX_FAILURE_MESSAGE = 1_000;
    /** A dismissal's or an override's reason, as its column holds it (V23, V33). */
    static final int MAX_REASON = 1_000;
    /**
     * A due date is in the past only once that day has ended everywhere ("anywhere on Earth"), so no reviewer's
     * today is ever refused, whatever their time zone; the page keeps them from picking their own yesterday.
     */
    private static final ZoneOffset ANYWHERE_ON_EARTH = ZoneOffset.ofHours(-12);

    private final InventoryService inventory;
    private final InventoryStore inventoryStore;
    private final ScoringService scoring;
    private final ChangeSignalStore signals;
    private final AiDrafter drafter;
    private final ObjectProvider<IssueTracker> trackers;
    private final RiskTicketStore store;
    private final UpgradeProposalService upgrades;
    private final DecisionTrail trail;
    private final ReachabilityAssessments assessments;

    public RiskTicketService(InventoryService inventory, InventoryStore inventoryStore, ScoringService scoring,
            ChangeSignalStore signals, AiDrafter drafter, ObjectProvider<IssueTracker> trackers, RiskTicketStore store,
            UpgradeProposalService upgrades, DecisionTrail trail, ReachabilityAssessments assessments) {
        this.inventory = inventory;
        this.inventoryStore = inventoryStore;
        this.scoring = scoring;
        this.signals = signals;
        this.drafter = drafter;
        this.trackers = trackers;
        this.store = store;
        this.upgrades = upgrades;
        this.trail = trail;
        this.assessments = assessments;
    }

    @Timed(value = "aegis.draft.duration", histogram = true,
            extraTags = {"kind", "ticket"})
    public RiskTicket propose(UUID changeSignalId, UUID serviceId, UUID drafterId) {
        inventory.getBusinessService(serviceId); // 404 outside the caller's organization, before any work
        ChangeImpactResponse impact = scoring.scoreChange(changeSignalId);
        SignalType type = impact.change().signalType();
        if (type != SignalType.VULNERABILITY && type != SignalType.VENDOR_OUTAGE) {
            throw new ApiExceptions.BadRequest("A risk ticket is drafted for a vulnerability or a vendor outage");
        }
        ScoredService scored = impact.services().stream()
                .filter(candidate -> candidate.service().id().equals(serviceId))
                .findFirst()
                .orElseThrow(() -> new ApiExceptions.Conflict("This change does not reach the service"));
        store.findLive(serviceId, changeSignalId).ifPresent(live -> {
            throw new ApiExceptions.Conflict("A response for this is already "
                    + live.status().name().toLowerCase(Locale.ROOT) + " as risk ticket " + live.id());
        });
        UpgradePlan plan = type == SignalType.VULNERABILITY ? upgrades.plan(changeSignalId, serviceId) : null;

        ChangeSignal signal = signals.findById(changeSignalId).orElse(null);
        // Feed text: redacted as it enters the draft, like the evidence and the AI's sections.
        String changeTitle = SensitiveText.redact(
                impact.change().title() == null ? impact.change().externalId() : impact.change().title());
        String title = RiskTicketText.title(type, changeTitle, scored.service().name());
        List<String> evidence = new ArrayList<>(RiskTicketText.evidence(scored));
        if (plan != null) {
            plan.notAutomated().forEach(reason -> evidence.add("Not automated: " + SensitiveText.redact(reason)));
        }
        evidence.addAll(DraftEvidence.source(impact.change(), signal));
        if (type == SignalType.VULNERABILITY) {
            // The AI's suggestion on whether the code calls the vulnerable function (E11-5): kept, else asked now.
            evidence.addAll(DraftEvidence.assessments(assessments.forDraft(serviceId, changeSignalId, drafterId)));
        }
        UUID id = UUID.randomUUID();
        Sections sections = sections(id, type, title, changeTitle, signal, scored, evidence);
        LocalDate drafted = LocalDate.now(ZoneOffset.UTC);
        RiskTicket draft = new RiskTicket(id, serviceId, changeSignalId, type, changeTitle, title,
                RiskTicketText.priority(scored.tier()), RiskTicketText.dueDate(scored.tier(), drafted), scored.score(),
                scored.tier(), RiskTicketText.labels(type, changeTitle, scored.tier()), sections.summary(),
                sections.risk(), sections.impact(), sections.actions(), evidence, sections.aiDrafted(),
                sections.citations(),
                RiskTicketStatus.PROPOSED, drafterId, null, null, null, null, null, null, null, null, null, null,
                0);
        String serviceName = SensitiveText.redact(scored.service().name());
        RiskTicket ticket = trail.recorded(() -> store.create(draft),
                saved -> record(saved, serviceName, Action.DRAFTED, drafterId, null));
        if (plan != null && plan.automatable()) {
            try {
                upgrades.draft(plan, ticket.id(), drafterId);
            } catch (RuntimeException failure) {
                // The ticket stands on its own; the pull request can be drafted again from the response.
                log.warn("response {}: the upgrade pull request was not drafted ({})", ticket.id(),
                        SensitiveText.redact(failure.toString()));
            }
        }
        return ticket;
    }

    /**
     * Approve the response: file the ticket, then open its pull request, if it has one awaiting a decision, with
     * the ticket's key in the title. Someone other than the drafter approves a response with a pull request; the
     * drafter may file one without. The ticket is claimed first (so a concurrent approval gets a conflict, not a
     * second issue); a failure to file is recorded on the ticket, which can then be approved again or dismissed,
     * and is rethrown. Once the issue is filed the ticket is never recorded as failing to file it: if Aegis can't
     * record the filing, the ticket stays filing and the approver is told the issue's key. A pull request that then
     * fails to open is recorded on it, to be approved again on its own.
     */
    public RiskTicket approve(UUID ticketId, UUID approver) {
        return approve(ticketId, approver, null);
    }

    /** As {@link #approve(UUID, UUID)}, refused if the response is no longer the version the approver saw. */
    public RiskTicket approve(UUID ticketId, UUID approver, Long seenVersion) {
        RiskTicket ticket = get(ticketId);
        expectVersion(ticket, seenVersion);
        UpgradeProposal pullRequest = currentPullRequest(ticketId).orElse(null);
        boolean opens = pullRequest != null && (pullRequest.status() == UpgradeProposalStatus.PROPOSED
                || pullRequest.status() == UpgradeProposalStatus.FAILED);
        if (opens && (ticket.createdBy().equals(approver) || pullRequest.createdBy().equals(approver))) {
            throw new ApiExceptions.Forbidden("You drafted this response, so someone else has to approve it. "
                    + "Leave its pull request out and you can file the ticket yourself");
        }
        Instant now = Instant.now();
        boolean interrupted = ticket.status() == RiskTicketStatus.FILING && ticket.decidedAt() != null
                && ticket.decidedAt().isBefore(now.minus(STALE_FILING));
        if (ticket.status() != RiskTicketStatus.PROPOSED && ticket.status() != RiskTicketStatus.FAILED && !interrupted) {
            throw new ApiExceptions.Conflict("A risk ticket that is "
                    + ticket.status().name().toLowerCase(Locale.ROOT) + " cannot be approved");
        }
        String serviceName = serviceName(ticket.serviceId());
        RiskTicket filing = trail.recorded(() -> store.update(ticket.filing(approver, now)),
                saved -> record(saved, serviceName, Action.APPROVED, approver, null));
        IssueTracker.FiledIssue issue;
        try {
            IssueTracker tracker = trackers.getIfAvailable();
            if (tracker == null) {
                throw new ApiExceptions.Conflict("No issue tracker is enabled on this Aegis deployment, so there is "
                        + "nowhere to file the ticket");
            }
            issue = tracker.file(RiskTicketText.issue(filing, serviceName, pullRequest));
        } catch (RuntimeException failure) {
            try {
                trail.recorded(() -> store.update(filing.failed(failureMessage(failure))),
                        saved -> record(saved, serviceName, Action.FILING_FAILED, approver, saved.failureMessage()));
            } catch (RuntimeException recording) {
                failure.addSuppressed(recording);
            }
            throw failure;
        }
        RiskTicket filed;
        try {
            filed = trail.recorded(() -> store.update(filing.filed(issue)),
                    saved -> record(saved, serviceName, Action.FILED, approver, null));
        } catch (RuntimeException recording) {
            log.error("response {}: filed as {} but not recorded ({}); it stays filing", ticketId, issue.key(),
                    recording.getClass().getSimpleName());
            ApiExceptions.Conflict unrecorded = new ApiExceptions.Conflict("Filed as " + issue.key() + ", but Aegis "
                    + "couldn't record it: the response stays filing. Check " + issue.key() + " before approving it "
                    + "again");
            unrecorded.initCause(recording);
            throw unrecorded;
        }
        if (opens) {
            try {
                upgrades.approveForResponse(pullRequest.id(), ticketId, approver, filed.issueKey());
            } catch (RuntimeException failure) {
                // Recorded on the pull request, which is approved again on its own; the ticket is filed.
                log.info("response {}: the pull request did not open ({})", ticketId, failure.getClass().getSimpleName());
            }
        }
        return filed;
    }

    /**
     * Approve the response's pull request on its own, once its ticket is filed: when it was left for later,
     * drafted afterwards, or failed to open. Its drafter never approves it.
     */
    public UpgradeProposal approvePullRequest(UUID ticketId, UUID proposalId, UUID approver) {
        RiskTicket ticket = get(ticketId);
        if (ticket.status() != RiskTicketStatus.FILED) {
            throw new ApiExceptions.Conflict("File the risk ticket first: approving the response files it and opens "
                    + "its pull request together");
        }
        return upgrades.approveForResponse(proposalId, ticketId, approver, ticket.issueKey());
    }

    /**
     * Draft the upgrade pull request of a response that has none: its first one was left out or failed to be
     * drafted, or its repository was linked afterwards. What still can't be automated is a conflict, with why.
     */
    public UpgradeProposal draftPullRequest(UUID ticketId, UUID drafterId) {
        RiskTicket ticket = get(ticketId);
        if (ticket.status() == RiskTicketStatus.DISMISSED) {
            throw new ApiExceptions.Conflict("A dismissed response gets no pull request");
        }
        if (ticket.signalType() != SignalType.VULNERABILITY) {
            throw new ApiExceptions.BadRequest("Only a vulnerability can be remediated by an upgrade");
        }
        currentPullRequest(ticketId).ifPresent(current -> {
            throw new ApiExceptions.Conflict("This response already has an upgrade pull request: " + current.id());
        });
        UpgradePlan plan = upgrades.plan(ticket.changeSignalId(), ticket.serviceId());
        return upgrades.draft(plan, ticketId, drafterId).orElseThrow(() -> new ApiExceptions.Conflict(
                plan.notAutomated().isEmpty()
                        ? "Aegis found nothing it can upgrade for this vulnerability in this service"
                        : "Nothing Aegis can automate: " + String.join("; ", plan.notAutomated())));
    }

    /**
     * The version a reviewer saw, if they name one, must still be the ticket's: otherwise someone else edited,
     * overrode or decided it meanwhile, and they decide again on what it is now.
     */
    private static void expectVersion(RiskTicket ticket, Long seenVersion) {
        if (seenVersion != null && seenVersion != ticket.version()) {
            throw new ApiExceptions.Conflict("This response changed after you opened it: someone else edited or "
                    + "decided it. Reload it and decide again");
        }
    }

    private static void awaitingDecision(RiskTicket ticket, String change) {
        if (ticket.status() != RiskTicketStatus.PROPOSED) {
            throw new ApiExceptions.Conflict("A risk ticket that is " + ticket.status().name().toLowerCase(Locale.ROOT)
                    + " cannot be " + change + ": only one awaiting a decision can");
        }
    }

    /** A reviewer's text, as it enters the record: trimmed, and redacted like everything else drafted. */
    private static String text(String value) {
        return value == null ? "" : SensitiveText.redact(value.strip());
    }

    /**
     * A reason, as it enters the record: trimmed, redacted, and within its column. Redacting can lengthen it (an
     * address of six characters becomes "[REDACTED]"), so the limit is applied after.
     */
    private static String reason(String value) {
        return value == null ? "" : RiskTicketText.cut(value.strip(), MAX_REASON);
    }

    /** The pull requests of these responses, newest first, by ticket. */
    public Map<UUID, List<UpgradeProposal>> pullRequests(Collection<UUID> ticketIds) {
        Map<UUID, List<UpgradeProposal>> byTicket = new HashMap<>();
        upgrades.forTickets(ticketIds).forEach(pullRequest ->
                byTicket.computeIfAbsent(pullRequest.riskTicketId(), id -> new ArrayList<>()).add(pullRequest));
        return byTicket;
    }

    /** Dismiss the response: the ticket, and its pull request if it hasn't opened, with the same reason. */
    public RiskTicket dismiss(UUID ticketId, UUID by, String reason) {
        return dismiss(ticketId, by, reason, null);
    }

    /** As {@link #dismiss(UUID, UUID, String)}, refused if the response is no longer the version they saw. */
    public RiskTicket dismiss(UUID ticketId, UUID by, String reason, Long seenVersion) {
        RiskTicket ticket = get(ticketId);
        expectVersion(ticket, seenVersion);
        if (ticket.status() != RiskTicketStatus.PROPOSED && ticket.status() != RiskTicketStatus.FAILED) {
            throw new ApiExceptions.Conflict("A risk ticket that is "
                    + ticket.status().name().toLowerCase(Locale.ROOT) + " cannot be dismissed");
        }
        RiskTicket dismissed = trail.recorded(() -> store.update(ticket.dismissed(by, Instant.now(), reason(reason))),
                saved -> record(saved, Action.DISMISSED, by, saved.dismissReason()));
        currentPullRequest(ticketId)
                .filter(pullRequest -> pullRequest.status() == UpgradeProposalStatus.PROPOSED
                        || pullRequest.status() == UpgradeProposalStatus.FAILED)
                .ifPresent(pullRequest -> upgrades.dismiss(pullRequest.id(), by, reason));
        return dismissed;
    }

    /**
     * Rewrite the drafted narrative before deciding: the summary, risk, impact and recommended actions, exactly as
     * they will be filed. Only while the response awaits a decision; the draft as it first was stays on record.
     */
    public RiskTicket edit(UUID ticketId, UUID by, long seenVersion, String summary, String risk, String impact,
            List<String> actions) {
        RiskTicket ticket = get(ticketId);
        expectVersion(ticket, seenVersion);
        awaitingDecision(ticket, "edited");
        String newSummary = text(summary);
        String newRisk = text(risk);
        String newImpact = text(impact);
        List<String> newActions = actions.stream().map(RiskTicketService::text).filter(action -> !action.isEmpty())
                .map(action -> action.replaceAll("\\s+", " ")).toList();
        if (newSummary.isEmpty() || newRisk.isEmpty() || newImpact.isEmpty() || newActions.isEmpty()) {
            throw new ApiExceptions.BadRequest("A ticket needs a summary, a risk, an impact and at least one action");
        }
        if (newSummary.equals(ticket.summary()) && newRisk.equals(ticket.risk()) && newImpact.equals(ticket.impact())
                && newActions.equals(ticket.actions())) {
            throw new ApiExceptions.BadRequest("Nothing was changed");
        }
        return trail.recorded(() -> store.update(ticket.edited(newSummary, newRisk, newImpact, newActions, by,
                Instant.now())), saved -> record(saved, Action.EDITED, by, null));
    }

    /**
     * Replace Aegis's priority and due date (worked out from the impact tier) before deciding, with a reason. Only
     * while the response awaits a decision; Aegis's own assessment and the reason stay on record, and the ticket
     * is filed with the reviewer's. A null due date means none. Overriding again may change only the reason; going
     * back to Aegis's own priority and due date is {@linkplain #withdrawOverride withdrawing} the override.
     */
    public RiskTicket override(UUID ticketId, UUID by, long seenVersion, IssueTracker.Priority priority,
            LocalDate dueDate, String reason) {
        RiskTicket ticket = get(ticketId);
        expectVersion(ticket, seenVersion);
        awaitingDecision(ticket, "overridden");
        String why = reason(reason);
        if (why.isEmpty()) {
            throw new ApiExceptions.BadRequest("Say why Aegis's assessment is being overridden");
        }
        if (dueDate != null && dueDate.isBefore(LocalDate.now(ANYWHERE_ON_EARTH))) {
            throw new ApiExceptions.BadRequest("The due date can't be in the past");
        }
        RiskTicket.AssessmentOverride current = ticket.override();
        IssueTracker.Priority assessedPriority = current == null ? ticket.priority() : current.assessedPriority();
        LocalDate assessedDueDate = current == null ? ticket.dueDate() : current.assessedDueDate();
        if (priority == assessedPriority && Objects.equals(dueDate, assessedDueDate)) {
            throw new ApiExceptions.BadRequest(current == null
                    ? "That is already the ticket's priority and due date"
                    : "That is Aegis's own priority and due date: withdraw the override instead");
        }
        if (current != null && priority == ticket.priority() && Objects.equals(dueDate, ticket.dueDate())
                && why.equals(current.reason())) {
            throw new ApiExceptions.BadRequest("Nothing was changed");
        }
        return trail.recorded(() -> store.update(ticket.overridden(priority, dueDate, why, by, Instant.now())),
                saved -> record(saved, Action.OVERRIDDEN, by, why));
    }

    /**
     * Withdraw the override before deciding: the ticket goes back to Aegis's own priority and due date. Only while
     * the response awaits a decision.
     */
    public RiskTicket withdrawOverride(UUID ticketId, UUID by, long seenVersion) {
        RiskTicket ticket = get(ticketId);
        expectVersion(ticket, seenVersion);
        awaitingDecision(ticket, "changed");
        if (ticket.override() == null) {
            throw new ApiExceptions.BadRequest("Aegis's priority and due date haven't been overridden");
        }
        return trail.recorded(() -> store.update(ticket.overrideWithdrawn()),
                saved -> record(saved, Action.OVERRIDE_WITHDRAWN, by, null));
    }

    public RiskTicket get(UUID ticketId) {
        return store.find(ticketId)
                .orElseThrow(() -> new ApiExceptions.NotFound("Risk ticket not found: " + ticketId));
    }

    public Page<RiskTicket> list(UUID serviceId, UUID changeSignalId, Collection<RiskTicketStatus> statuses,
            Boolean pending, Pageable pageable) {
        return store.findPage(serviceId, changeSignalId, statuses, pending, pageable);
    }

    /** The ticket as a ServiceNow incident, for organizations that track risks there. */
    public Map<String, Object> serviceNow(RiskTicket ticket) {
        return RiskTicketText.serviceNow(ticket, serviceNames(List.of(ticket.serviceId())).get(ticket.serviceId()),
                currentPullRequest(ticket.id()).orElse(null));
    }

    /** The response's pull request that isn't dismissed, if any: at most one ever is (V27). */
    private Optional<UpgradeProposal> currentPullRequest(UUID ticketId) {
        return upgrades.forTickets(List.of(ticketId)).stream()
                .filter(pullRequest -> pullRequest.status() != UpgradeProposalStatus.DISMISSED)
                .findFirst();
    }

    /** The current names of these services (org-scoped), for display next to their tickets. */
    public Map<UUID, String> serviceNames(Collection<UUID> serviceIds) {
        if (serviceIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        inventoryStore.findBusinessServicesByIds(serviceIds)
                .forEach((id, service) -> names.put(id, SensitiveText.redact(service.getName())));
        return names;
    }

    /** The current (redacted) name of one service in the caller's organization; null if it is gone. */
    private String serviceName(UUID serviceId) {
        return serviceNames(List.of(serviceId)).get(serviceId);
    }

    /** A step on this ticket for the decision trail, with the ticket as it now stands. */
    private DecisionRecord.Entry record(RiskTicket ticket, Action action, UUID actor, String reason) {
        return record(ticket, serviceName(ticket.serviceId()), action, actor, reason);
    }

    /** As {@link #record(RiskTicket, Action, UUID, String)}, with its service's name already read. */
    private static DecisionRecord.Entry record(RiskTicket ticket, String serviceName, Action action, UUID actor,
            String reason) {
        return new DecisionRecord.Entry(Subject.RISK_TICKET, ticket.id(), ticket.id(), ticket.serviceId(), serviceName,
                List.of(ticket.serviceId()), ticket.changeSignalId(), ticket.changeTitle(), action, actor, reason,
                ticket.score(), ticket.tier(), ticket);
    }

    private Sections sections(UUID ticketId, SignalType type, String title, String changeTitle, ChangeSignal signal,
            ScoredService scored, List<String> evidence) {
        String summary = signal == null ? null : signal.summary();
        String vendor = scored.evidence().stream().map(EvidenceChain::vendor).filter(Objects::nonNull)
                .map(vendorRef -> vendorRef.name()).findFirst().orElse(null);
        try {
            AiDtos.Draft draft = drafter.draft(new AiDtos.DraftRequest(ticketId.toString(), "risk_ticket",
                    context(type, changeTitle, signal, scored, evidence, vendor)));
            AiDtos.TicketSections ticket = draft.ticket();
            if (ticket == null || blank(ticket.summary()) || blank(ticket.risk()) || blank(ticket.impact())
                    || ticket.actions() == null || ticket.actions().isEmpty()) {
                throw new IllegalStateException("the AI service returned no ticket sections");
            }
            return new Sections(ticket.summary().strip(), ticket.risk().strip(), ticket.impact().strip(),
                    ticket.actions().stream().map(String::strip).filter(action -> !action.isEmpty())
                            .map(action -> action.replaceAll("\\s+", " ")).toList(), true,
                    draft.citations() == null ? List.of() : List.copyOf(draft.citations()));
        } catch (RuntimeException unavailable) {
            // The facts stand on their own: an AI outage must never block a ticket.
            log.warn("risk ticket {}: no AI draft ({})", ticketId, unavailable.getClass().getSimpleName());
            return RiskTicketText.fallback(type, title, summary, scored, vendor);
        }
    }

    /** The ai-service's grounding: the change's own text and the deterministic facts, nothing else. */
    private static String context(SignalType type, String changeTitle, ChangeSignal signal, ScoredService scored,
            List<String> evidence, String vendor) {
        String description = signal == null || signal.summary() == null || signal.summary().isBlank()
                ? "(no description published)"
                : RiskTicketText.cut(signal.summary().strip(), MAX_CHANGE_CONTEXT);
        String when = signal == null || signal.occurredAt() == null ? "" : ", " + signal.occurredAt().toString().substring(0, 10);
        String owners = scored.owners().isEmpty() ? "none assigned"
                : scored.owners().size() + " assigned (personal details omitted)";
        return """
                %s %s%s%s: %s
                Affected service: %s (%s criticality, %s).
                Impact score %.1f (%s%s), worked out by Aegis.
                Evidence:
                %s
                Service owners: %s.
                """.formatted(type == SignalType.VENDOR_OUTAGE ? "Vendor outage" : "Vulnerability", changeTitle,
                vendor == null ? "" : " at " + vendor, when, description, scored.service().name(),
                scored.service().criticality(), scored.service().exposure(), scored.score(), scored.tier(),
                scored.material() ? ", material" : "",
                evidence.stream().map(line -> "- " + line).collect(Collectors.joining("\n")), owners);
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    /** What the ticket records about a failure: an API error's own (safe) message, else a generic one. */
    private static String failureMessage(RuntimeException failure) {
        String message = failure instanceof ApiExceptions.NotFound || failure instanceof ApiExceptions.Conflict
                || failure instanceof ApiExceptions.BadRequest || failure instanceof ApiExceptions.Forbidden
                || failure instanceof ApiExceptions.UpstreamUnavailable
                ? failure.getMessage()
                : "Filing the ticket failed";
        return message.length() <= MAX_FAILURE_MESSAGE ? message : message.substring(0, MAX_FAILURE_MESSAGE);
    }
}
