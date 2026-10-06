package com.aegis.core.findings;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.aegis.core.findings.FindingDtos.Item;
import com.aegis.core.findings.FindingDtos.Queue;
import com.aegis.core.findings.FindingDtos.ServiceImpact;
import com.aegis.core.inventory.InventoryFilters.ServiceFilter;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.matching.MatchingDtos.OwnerRef;
import com.aegis.core.matching.MatchingDtos.ServiceRef;
import com.aegis.core.matching.VulnerableDependencies;
import com.aegis.core.scoring.ScoringDtos.ChangeImpactResponse;
import com.aegis.core.scoring.ScoringDtos.ScoredService;
import com.aegis.core.scoring.ScoringService;
import com.aegis.core.signals.ChangeSignal;
import com.aegis.core.signals.ChangeSignal.SignalType;
import com.aegis.core.signals.ChangeSignalFilter;
import com.aegis.core.signals.ChangeSignalStore;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * E9's live material queue. Vulnerability candidates come from the estate-side batched search, so old
 * CVEs and transitive dependencies remain visible without scanning the global advisory feed. Outages
 * use matching's vendor correlation. Security news has no matchable identity and cannot be scored.
 * There is no date cutoff, signal cap, AI call or action here. Each score uses the existing scorer;
 * detail uses that same scoring API and the existing response API. Decisions remain in responses.
 */
@Service
public class FindingService {
    private final InventoryStore inventory;
    private final VulnerableDependencies vulnerable;
    private final ChangeSignalStore signals;
    private final ScoringService scoring;

    public FindingService(InventoryStore inventory, VulnerableDependencies vulnerable,
            ChangeSignalStore signals, ScoringService scoring) {
        this.inventory = inventory;
        this.vulnerable = vulnerable;
        this.signals = signals;
        this.scoring = scoring;
    }

    // Deliberately no outer transaction: each org-scoped store and scoring call owns its transaction.
    public Queue list(UUID serviceId, UUID ownerId, UUID userId, boolean mine, Pageable pageable) {
        List<BusinessService> estate = inventory.findBusinessServices(ServiceFilter.NONE, Pageable.unpaged())
                .stream().sorted(Comparator.comparing(BusinessService::getName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(BusinessService::getId)).toList();
        List<UUID> estateIds = estate.stream().map(BusinessService::getId).toList();
        Map<UUID, List<Owner>> assignments = inventory.findOwnersForServices(estateIds);
        int owned = (int) estateIds.stream().filter(id -> assignments.getOrDefault(id, List.of()).stream()
                .anyMatch(owner -> owner.getUserId().equals(userId))).count();
        Set<UUID> selected = new LinkedHashSet<>();
        for (UUID id : estateIds) {
            List<Owner> owners = assignments.getOrDefault(id, List.of());
            if ((serviceId == null || serviceId.equals(id))
                    && (ownerId == null || owners.stream().anyMatch(owner -> owner.getId().equals(ownerId)))
                    && (!mine || owners.stream().anyMatch(owner -> owner.getUserId().equals(userId)))) {
                selected.add(id);
            }
        }

        Set<UUID> candidates = new LinkedHashSet<>();
        if (!selected.isEmpty()) {
            vulnerable.find(selected).dependencies().forEach(dependency -> dependency.vulnerabilities().stream()
                    .map(ref -> ref.changeSignalId()).filter(Objects::nonNull).forEach(candidates::add));
            signals.findPage(new ChangeSignalFilter(SignalType.VENDOR_OUTAGE, null, null, null, null, null, null),
                    Pageable.unpaged()).stream().map(ChangeSignal::id).forEach(candidates::add);
        }

        List<Item> ranked = candidates.stream().map(scoring::scoreChange)
                .map(impact -> item(impact, selected)).filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(Item::score).reversed()
                        .thenComparing(item -> item.change().id())).toList();
        int start = (int) Math.min(pageable.getOffset(), ranked.size());
        int end = (int) Math.min((long) start + pageable.getPageSize(), ranked.size());
        List<ServiceRef> services = estate.stream().map(service -> new ServiceRef(service.getId(),
                service.getName(), service.getCriticality(), service.getExposure())).toList();
        List<OwnerRef> owners = assignments.values().stream().flatMap(List::stream)
                .map(owner -> new OwnerRef(owner.getId(), owner.getUserId(), owner.getName())).distinct()
                .sorted(Comparator.comparing(OwnerRef::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(OwnerRef::id)).toList();
        return new Queue(new PageImpl<>(ranked.subList(start, end), pageable, ranked.size()), services, owners, owned);
    }

    private static Item item(ChangeImpactResponse impact, Set<UUID> selected) {
        List<ScoredService> material = impact.services().stream()
                .filter(service -> service.material() && selected.contains(service.service().id()))
                .sorted(Comparator.comparingDouble(ScoredService::score).reversed()
                        .thenComparing(service -> service.service().id())).toList();
        if (material.isEmpty()) return null;
        ScoredService worst = material.getFirst();
        return new Item(impact.change(), worst.score(), worst.tier(), material.stream().map(service ->
                new ServiceImpact(service.service(), service.score(), service.tier(), service.confidence(),
                        service.owners(), service.explanation())).toList());
    }
}
