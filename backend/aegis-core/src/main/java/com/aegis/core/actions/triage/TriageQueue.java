package com.aegis.core.actions.triage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.aegis.core.actions.ticket.RiskTicketStore;
import com.aegis.core.actions.upgrade.ResponseDirectory;
import com.aegis.core.actions.upgrade.ResponseDirectory.ResponseRef;
import com.aegis.core.actions.upgrade.ResponseDirectory.ServiceChange;
import com.aegis.core.actions.upgrade.UpgradeRecommendations;
import com.aegis.core.actions.upgrade.UpgradeRecommendations.Recommendation;
import com.aegis.core.incidents.VendorIncident;
import com.aegis.core.incidents.VendorIncidentStore;
import com.aegis.core.inventory.InventoryFilters.ServiceFilter;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SbomImportSource;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.matching.MatchingDtos.OwnerRef;
import com.aegis.core.matching.MatchingDtos.ServiceRef;
import com.aegis.core.scoring.ScoringDtos.ChangeImpactResponse;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.scoring.ScoringDtos.ScoredService;
import com.aegis.core.scoring.ScoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * E9's triage queue (story 1): every material vulnerability and vendor outage reaching the organization's
 * services, highest impact first, with what fixes it and the response already drafted for it — what to respond to
 * next. Vulnerable dependencies are the upgrade recommendations ({@link UpgradeRecommendations}: fix version and
 * readiness included, worked out in a fixed number of batched lookups however large the estate); outages are the
 * ones still in progress ({@link VendorIncidentStore#findActive}), each scored once. A security news item has no
 * package or vendor to correlate, so it has no score and no place here. Recomputed per request; nothing is
 * persisted, and drafting and deciding stay with responses. {@link #ownedBy} is the same queue seen by a service
 * owner, one service at a time (story 2).
 */
@Service
public class TriageQueue {

    private static final Logger log = LoggerFactory.getLogger(TriageQueue.class);

    /** An outage its vendor hasn't updated for this long is over, even when the feed never says so (plain RSS). */
    static final Duration OUTAGE_QUIET_AFTER = Duration.ofDays(14);

    private static final Comparator<Item> RANKING = Comparator.comparingDouble(Item::score).reversed()
            .thenComparing(item -> item.service().name(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Item::kind)
            .thenComparing(TriageQueue::subject, String.CASE_INSENSITIVE_ORDER);

    private final InventoryStore inventory;
    private final UpgradeRecommendations recommendations;
    private final VendorIncidentStore incidents;
    private final ScoringService scoring;
    private final ResponseDirectory responses;
    private final RiskTicketStore tickets;

    public TriageQueue(InventoryStore inventory, UpgradeRecommendations recommendations, VendorIncidentStore incidents,
            ScoringService scoring, ResponseDirectory responses, RiskTicketStore tickets) {
        this.inventory = inventory;
        this.recommendations = recommendations;
        this.incidents = incidents;
        this.scoring = scoring;
        this.responses = responses;
        this.tickets = tickets;
    }

    public enum Kind { VULNERABILITY, VENDOR_OUTAGE }

    /** Who is asking: whether they draft for any service ({@code ANALYST}) or for the ones they own. */
    public record Caller(UUID userId, boolean analyst, boolean serviceOwner) {
    }

    /** Every filter is optional. Service and owner constrain the same service; an ecosystem leaves outages out. */
    public record Filters(UUID serviceId, UUID ownerId, boolean mine, String ecosystem, ImpactTier tier) {
    }

    /** A vendor outage still in progress, as its status page describes it. */
    public record Outage(UUID id, String title, String vendorName, String status, String impact, Instant startedAt,
                         Instant lastUpdateAt, String url) {
    }

    /**
     * One thing to respond to in one service: a vulnerable dependency, with the upgrade that fixes it
     * ({@code upgrade}), or a vendor outage ({@code outage}). {@code changeSignalId} is the change a response
     * answers, {@code response} the one already drafted, and {@code canDraft} whether the caller may draft one.
     */
    public record Item(Kind kind, UUID changeSignalId, ServiceRef service, List<OwnerRef> owners, double score,
                       ImpactTier tier, MatchConfidence confidence, Recommendation upgrade, Outage outage,
                       ResponseRef response, boolean canDraft) {
    }

    /** A page of items, and the filter options of the queue's scope (every service, or the caller's own). */
    public record Queue(Page<Item> items, List<ServiceRef> services, List<OwnerRef> owners, List<String> ecosystems) {
    }

    /**
     * A service the caller is an assigned owner of, as its owner looks after it: how many items it has to respond
     * to, how many of those have no response yet, and the worst one's score and tier (null when there is none);
     * the responses for it still waiting in Review & approve; and where Aegis's view of its dependencies comes
     * from. Without a repository, a response is the ticket alone; without an SBOM, Aegis can't see what it uses.
     */
    public record OwnedService(ServiceRef service, int toRespond, int noResponseYet, Double topScore,
                               ImpactTier topTier, long awaitingDecision, String repositoryUrl,
                               Instant sbomImportedAt, SbomImportSource sbomImportSource) {
    }

    /** Every service in the organization, by name, and each one's assigned owners. */
    private record Estate(List<BusinessService> services, Map<UUID, List<Owner>> assignments) {

        List<Owner> ownersOf(UUID serviceId) {
            return assignments.getOrDefault(serviceId, List.of());
        }
    }

    // Deliberately no outer transaction: each org-scoped store and scoring call owns its own.
    public Queue list(Filters filters, Caller caller, Pageable pageable) {
        Estate estate = estate();
        Map<UUID, List<Owner>> assignments = estate.assignments();
        List<BusinessService> scope = estate.services().stream()
                .filter(service -> !filters.mine() || owns(caller, estate.ownersOf(service.getId())))
                .toList();
        Map<UUID, BusinessService> selected = new LinkedHashMap<>();
        for (BusinessService service : scope) {
            if ((filters.serviceId() == null || filters.serviceId().equals(service.getId()))
                    && (filters.ownerId() == null || estate.ownersOf(service.getId()).stream()
                            .anyMatch(owner -> owner.getId().equals(filters.ownerId())))) {
                selected.put(service.getId(), service);
            }
        }

        List<Item> material = material(selected, assignments, caller);
        List<String> ecosystems = material.stream().filter(item -> item.upgrade() != null)
                .map(item -> item.upgrade().component().ecosystem()).distinct().sorted().toList();
        List<Item> ranked = material.stream()
                .filter(item -> filters.ecosystem() == null || (item.upgrade() != null
                        && filters.ecosystem().equalsIgnoreCase(item.upgrade().component().ecosystem())))
                .filter(item -> filters.tier() == null || item.tier() == filters.tier())
                .sorted(RANKING)
                .toList();
        int start = (int) Math.min(pageable.getOffset(), ranked.size());
        int end = (int) Math.min((long) start + pageable.getPageSize(), ranked.size());

        List<OwnerRef> owners = scope.stream()
                .flatMap(service -> estate.ownersOf(service.getId()).stream())
                .map(TriageQueue::ownerRef).distinct()
                .sorted(Comparator.comparing(OwnerRef::name, String.CASE_INSENSITIVE_ORDER).thenComparing(OwnerRef::id))
                .toList();
        return new Queue(new PageImpl<>(ranked.subList(start, end), pageable, ranked.size()),
                scope.stream().map(TriageQueue::serviceRef).toList(), owners, ecosystems);
    }

    /**
     * E9 story 2: the services the caller is an assigned owner of, by name, each with what it needs from them. The
     * same items as {@link #list} with {@code mine}, counted per service, so the two never disagree.
     */
    public List<OwnedService> ownedBy(Caller caller) {
        Estate estate = estate();
        Map<UUID, BusinessService> owned = new LinkedHashMap<>();
        for (BusinessService service : estate.services()) {
            if (owns(caller, estate.ownersOf(service.getId()))) {
                owned.put(service.getId(), service);
            }
        }
        Map<UUID, List<Item>> items = material(owned, estate.assignments(), caller).stream()
                .collect(Collectors.groupingBy(item -> item.service().id()));
        return owned.values().stream().map(service -> {
            List<Item> its = items.getOrDefault(service.getId(), List.of());
            Item worst = its.stream().max(Comparator.comparingDouble(Item::score)).orElse(null);
            // Pending as Review & approve counts it: the ticket or its pull request still needs a person.
            long awaiting = tickets.findPage(service.getId(), null, null, true, PageRequest.of(0, 1))
                    .getTotalElements();
            return new OwnedService(serviceRef(service), its.size(),
                    (int) its.stream().filter(item -> item.response() == null).count(),
                    worst == null ? null : worst.score(), worst == null ? null : worst.tier(), awaiting,
                    service.getRepositoryUrl(), service.getSbomImportedAt(), service.getSbomImportSource());
        }).toList();
    }

    private Estate estate() {
        List<BusinessService> services = inventory.findBusinessServices(ServiceFilter.NONE, Pageable.unpaged())
                .stream()
                .sorted(Comparator.comparing(BusinessService::getName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(BusinessService::getId))
                .toList();
        return new Estate(services,
                inventory.findOwnersForServices(services.stream().map(BusinessService::getId).toList()));
    }

    /** The material items in these services: their vulnerable dependencies, and the outages reaching them. */
    private List<Item> material(Map<UUID, BusinessService> selected, Map<UUID, List<Owner>> assignments,
            Caller caller) {
        if (selected.isEmpty()) {
            return List.of();
        }
        List<Item> material = new ArrayList<>();
        for (Recommendation upgrade : recommendations.recommendFor(selected)) {
            if (upgrade.material()) {
                material.add(vulnerability(upgrade, selected.get(upgrade.serviceId()), assignments, caller));
            }
        }
        material.addAll(outages(selected, assignments, caller));
        return material;
    }

    private Item vulnerability(Recommendation upgrade, BusinessService service, Map<UUID, List<Owner>> assignments,
            Caller caller) {
        List<Owner> owners = assignments.getOrDefault(service.getId(), List.of());
        return new Item(Kind.VULNERABILITY, upgrade.changeSignalId(), serviceRef(service), ownerRefs(owners),
                upgrade.score(), upgrade.tier(), upgrade.confidence(), upgrade, null, upgrade.response(),
                canDraft(caller, owners));
    }

    /** Each outage in progress, in every selected service it reaches materially. */
    private List<Item> outages(Map<UUID, BusinessService> selected, Map<UUID, List<Owner>> assignments,
            Caller caller) {
        List<VendorIncident> active = incidents.findActive(Instant.now().minus(OUTAGE_QUIET_AFTER));
        if (active.isEmpty()) {
            return List.of();
        }
        Map<ServiceChange, ResponseRef> answered = responses.current(selected.keySet());
        List<Item> items = new ArrayList<>();
        for (VendorIncident incident : active) {
            ChangeImpactResponse impact;
            try {
                impact = scoring.scoreChange(incident.id());
            } catch (RuntimeException unscored) {
                // One outage gone since it was listed, or with odd data, must never take the whole queue down.
                log.warn("triage: outage {} not scored ({})", incident.id(), unscored.toString());
                continue;
            }
            Outage outage = new Outage(incident.id(), incident.title(), incident.vendorName(), incident.status(),
                    incident.impact(), incident.startedAt(), incident.lastUpdateAt(), incident.url());
            for (ScoredService scored : impact.services()) {
                if (!scored.material() || !selected.containsKey(scored.service().id())) {
                    continue;
                }
                List<Owner> owners = assignments.getOrDefault(scored.service().id(), List.of());
                items.add(new Item(Kind.VENDOR_OUTAGE, incident.id(), scored.service(), ownerRefs(owners),
                        scored.score(), scored.tier(), scored.confidence(), null, outage,
                        answered.get(new ServiceChange(scored.service().id(), incident.id())),
                        canDraft(caller, owners)));
            }
        }
        return items;
    }

    /** As {@code TicketRoles.PROPOSE}: an analyst for any service, a service owner for the ones assigned to them. */
    private static boolean canDraft(Caller caller, List<Owner> owners) {
        return caller.analyst() || (caller.serviceOwner() && owns(caller, owners));
    }

    private static boolean owns(Caller caller, List<Owner> owners) {
        return owners != null && owners.stream().anyMatch(owner -> caller.userId().equals(owner.getUserId()));
    }

    private static List<OwnerRef> ownerRefs(List<Owner> owners) {
        return owners.stream().map(TriageQueue::ownerRef)
                .sorted(Comparator.comparing(OwnerRef::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private static OwnerRef ownerRef(Owner owner) {
        return new OwnerRef(owner.getId(), owner.getUserId(), owner.getName());
    }

    private static ServiceRef serviceRef(BusinessService service) {
        return new ServiceRef(service.getId(), service.getName(), service.getCriticality(), service.getExposure());
    }

    private static String subject(Item item) {
        if (item.upgrade() != null) {
            return item.upgrade().component().name() + " " + item.upgrade().component().version();
        }
        return item.outage().title() == null ? "" : item.outage().title();
    }
}
