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

import com.aegis.core.actions.evidence.DraftEvidence;
import com.aegis.core.actions.ticket.RiskTicketText.Sections;
import com.aegis.core.actions.tracker.IssueTracker;
import com.aegis.core.actions.upgrade.UpgradePlan;
import com.aegis.core.actions.upgrade.UpgradeProposal;
import com.aegis.core.actions.upgrade.UpgradeProposalService;
import com.aegis.core.actions.upgrade.UpgradeProposalStatus;
import com.aegis.core.ai.AiDrafter;
import com.aegis.core.ai.AiDtos;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.matching.MatchingDtos.EvidenceChain;
import com.aegis.core.matching.MatchingDtos.OwnerRef;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.scoring.ScoringDtos.ChangeImpactResponse;
import com.aegis.core.scoring.ScoringDtos.ScoredService;
import com.aegis.core.scoring.ScoringService;
import com.aegis.core.signals.ChangeSignal;
import com.aegis.core.signals.ChangeSignal.SignalType;
import com.aegis.core.signals.ChangeSignalStore;
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
 */
@Service
public class RiskTicketService {

    private static final Logger log = LoggerFactory.getLogger(RiskTicketService.class);

    /** An approval interrupted mid-way (a crash while filing) may be retried after this long. */
    static final Duration STALE_FILING = Duration.ofMinutes(10);
    private static final int MAX_CHANGE_CONTEXT = 4_000;
    private static final int MAX_FAILURE_MESSAGE = 1_000;

    private final InventoryService inventory;
    private final InventoryStore inventoryStore;
    private final ScoringService scoring;
    private final ChangeSignalStore signals;
    private final AiDrafter drafter;
    private final ObjectProvider<IssueTracker> trackers;
    private final RiskTicketStore store;
    private final UpgradeProposalService upgrades;

    public RiskTicketService(InventoryService inventory, InventoryStore inventoryStore, ScoringService scoring,
            ChangeSignalStore signals, AiDrafter drafter, ObjectProvider<IssueTracker> trackers, RiskTicketStore store,
            UpgradeProposalService upgrades) {
        this.inventory = inventory;
        this.inventoryStore = inventoryStore;
        this.scoring = scoring;
        this.signals = signals;
        this.drafter = drafter;
        this.trackers = trackers;
        this.store = store;
        this.upgrades = upgrades;
    }

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
        String changeTitle = impact.change().title() == null ? impact.change().externalId() : impact.change().title();
        String title = RiskTicketText.title(type, changeTitle, scored.service().name());
        List<String> evidence = new ArrayList<>(RiskTicketText.evidence(scored));
        if (plan != null) {
            plan.notAutomated().forEach(reason -> evidence.add("Not automated: " + reason));
        }
        evidence.addAll(DraftEvidence.source(impact.change(), signal));
        UUID id = UUID.randomUUID();
        Sections sections = sections(id, type, title, changeTitle, signal, scored, evidence);
        LocalDate drafted = LocalDate.now(ZoneOffset.UTC);
        RiskTicket ticket = store.create(new RiskTicket(id, serviceId, changeSignalId, type, changeTitle, title,
                RiskTicketText.priority(scored.tier()), RiskTicketText.dueDate(scored.tier(), drafted), scored.score(),
                scored.tier(), RiskTicketText.labels(type, changeTitle, scored.tier()), sections.summary(),
                sections.risk(), sections.impact(), sections.actions(), evidence, sections.aiDrafted(),
                sections.citations(),
                RiskTicketStatus.PROPOSED, drafterId, null, null, null, null, null, null, null, 0));
        if (plan != null && plan.automatable()) {
            try {
                upgrades.draft(plan, ticket.id(), drafterId);
            } catch (RuntimeException failure) {
                // The ticket stands on its own; the pull request can be drafted again from the response.
                log.warn("response {}: the upgrade pull request was not drafted ({})", ticket.id(), failure.toString());
            }
        }
        return ticket;
    }

    /**
     * Approve the response: file the ticket, then open its pull request, if it has one awaiting a decision, with
     * the ticket's key in the title. Someone other than the drafter approves a response with a pull request; the
     * drafter may file one without. The ticket is claimed first (so a concurrent approval gets a conflict, not a
     * second issue); a failure to file is recorded on the ticket, which can then be approved again or dismissed,
     * and is rethrown. A pull request that then fails to open is recorded on it, to be approved again on its own.
     */
    public RiskTicket approve(UUID ticketId, UUID approver) {
        RiskTicket ticket = get(ticketId);
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
        RiskTicket filing = store.update(ticket.filing(approver, now));
        RiskTicket filed;
        try {
            IssueTracker tracker = trackers.getIfAvailable();
            if (tracker == null) {
                throw new ApiExceptions.Conflict("No issue tracker is enabled on this Aegis deployment, so there is "
                        + "nowhere to file the ticket");
            }
            String serviceName = serviceNames(List.of(filing.serviceId())).get(filing.serviceId());
            filed = store.update(filing.filed(tracker.file(RiskTicketText.issue(filing, serviceName, pullRequest))));
        } catch (RuntimeException failure) {
            try {
                store.update(filing.failed(failureMessage(failure)));
            } catch (RuntimeException recording) {
                failure.addSuppressed(recording);
            }
            throw failure;
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

    /** The pull requests of these responses, newest first, by ticket. */
    public Map<UUID, List<UpgradeProposal>> pullRequests(Collection<UUID> ticketIds) {
        Map<UUID, List<UpgradeProposal>> byTicket = new HashMap<>();
        upgrades.forTickets(ticketIds).forEach(pullRequest ->
                byTicket.computeIfAbsent(pullRequest.riskTicketId(), id -> new ArrayList<>()).add(pullRequest));
        return byTicket;
    }

    /** Dismiss the response: the ticket, and its pull request if it hasn't opened, with the same reason. */
    public RiskTicket dismiss(UUID ticketId, UUID by, String reason) {
        RiskTicket ticket = get(ticketId);
        if (ticket.status() != RiskTicketStatus.PROPOSED && ticket.status() != RiskTicketStatus.FAILED) {
            throw new ApiExceptions.Conflict("A risk ticket that is "
                    + ticket.status().name().toLowerCase(Locale.ROOT) + " cannot be dismissed");
        }
        RiskTicket dismissed = store.update(ticket.dismissed(by, Instant.now(), reason.trim()));
        currentPullRequest(ticketId)
                .filter(pullRequest -> pullRequest.status() == UpgradeProposalStatus.PROPOSED
                        || pullRequest.status() == UpgradeProposalStatus.FAILED)
                .ifPresent(pullRequest -> upgrades.dismiss(pullRequest.id(), by, reason));
        return dismissed;
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
        inventoryStore.findBusinessServicesByIds(serviceIds).forEach((id, service) -> names.put(id, service.getName()));
        return names;
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
                : scored.owners().stream().map(OwnerRef::name).collect(Collectors.joining(", "));
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
