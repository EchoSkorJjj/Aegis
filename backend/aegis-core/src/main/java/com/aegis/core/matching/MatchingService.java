package com.aegis.core.matching;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.aegis.core.common.ApiExceptions;
import com.aegis.core.ingestion.advisory.Advisory;
import com.aegis.core.ingestion.advisory.AdvisoryStore;
import com.aegis.core.ingestion.incident.VendorIncident;
import com.aegis.core.ingestion.incident.VendorIncidentStore;
import com.aegis.core.ingestion.signal.ChangeSignal;
import com.aegis.core.ingestion.signal.ChangeSignalStore;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.InventoryStore.ComponentDependencyLink;
import com.aegis.core.inventory.InventoryStore.ServiceComponentLink;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import com.aegis.core.matching.AdvisoryComponentMatcher.ComponentMatch;
import com.aegis.core.matching.MatchingDtos.AffectedProductEvidence;
import com.aegis.core.matching.MatchingDtos.ChangeMatchResponse;
import com.aegis.core.matching.MatchingDtos.ChangeRef;
import com.aegis.core.matching.MatchingDtos.ComponentRef;
import com.aegis.core.matching.MatchingDtos.EvidenceChain;
import com.aegis.core.matching.MatchingDtos.EvidenceKind;
import com.aegis.core.matching.MatchingDtos.ImpactedServiceMatch;
import com.aegis.core.matching.MatchingDtos.OwnerRef;
import com.aegis.core.matching.MatchingDtos.ServiceRef;
import com.aegis.core.matching.MatchingDtos.VendorRef;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application service for Epic 6's deterministic match, traversal, and evidence generation. */
@Service
public class MatchingService {

    private final ChangeSignalStore signals;
    private final AdvisoryStore advisories;
    private final VendorIncidentStore incidents;
    private final InventoryStore inventory;
    private final AdvisoryComponentMatcher componentMatcher;

    public MatchingService(
            ChangeSignalStore signals,
            AdvisoryStore advisories,
            VendorIncidentStore incidents,
            InventoryStore inventory,
            AdvisoryComponentMatcher componentMatcher) {
        this.signals = signals;
        this.advisories = advisories;
        this.incidents = incidents;
        this.inventory = inventory;
        this.componentMatcher = componentMatcher;
    }

    @Transactional(readOnly = true)
    public ChangeMatchResponse matchChange(UUID signalId) {
        ChangeSignal signal = signals.findById(signalId)
                .orElseThrow(() -> new ApiExceptions.NotFound("Change signal not found: " + signalId));
        return switch (signal.signalType()) {
            case VULNERABILITY -> matchAdvisory(signal, requireAdvisory(signal));
            case VENDOR_OUTAGE -> matchVendorIncident(signal, requireIncident(signal));
            case SECURITY_NEWS -> throw new ApiExceptions.BadRequest(
                    "Security-news signals have no package or vendor identity to match directly");
        };
    }

    private Advisory requireAdvisory(ChangeSignal signal) {
        return advisories.findByExternalId(signal.sourceId(), signal.externalId())
                .orElseThrow(() -> new ApiExceptions.NotFound("Advisory backing change signal was not found"));
    }

    private VendorIncident requireIncident(ChangeSignal signal) {
        return incidents.findByExternalId(signal.sourceId(), signal.externalId())
                .orElseThrow(() -> new ApiExceptions.NotFound("Vendor incident backing change signal was not found"));
    }

    private ChangeMatchResponse matchAdvisory(ChangeSignal signal, Advisory advisory) {
        List<ComponentMatch> componentMatches = componentMatcher.match(
                advisory, inventory.findSoftwareComponentsForMatching());
        Map<UUID, ServiceAccumulator> impacted = new LinkedHashMap<>();
        Set<UUID> matchedComponentIds = new LinkedHashSet<>();

        for (ComponentMatch match : componentMatches) {
            matchedComponentIds.add(match.component().getId());
            Map<UUID, List<UUID>> paths = dependentPaths(match.component().getId());
            List<ServiceComponentLink> serviceLinks = inventory.findServiceComponentLinks(paths.keySet());
            if (serviceLinks.isEmpty()) {
                continue;
            }
            Set<UUID> serviceIds = serviceLinks.stream()
                    .map(ServiceComponentLink::serviceId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<UUID, BusinessService> services = inventory.findBusinessServicesByIds(serviceIds);
            Map<UUID, List<Owner>> owners = inventory.findOwnersForServices(serviceIds);
            Map<UUID, SoftwareComponent> components = inventory.findSoftwareComponentsByIds(paths.keySet());

            serviceLinks.stream()
                    .sorted(Comparator.comparing(ServiceComponentLink::serviceId)
                            .thenComparing(ServiceComponentLink::componentId))
                    .forEach(link -> {
                        BusinessService service = services.get(link.serviceId());
                        if (service == null) {
                            return;
                        }
                        List<OwnerRef> ownerRefs = ownerRefs(owners.get(link.serviceId()));
                        List<ComponentRef> componentPath = paths.get(link.componentId()).stream()
                                .map(components::get)
                                .filter(java.util.Objects::nonNull)
                                .map(ComponentRef::from)
                                .toList();
                        EvidenceChain evidence = new EvidenceChain(
                                EvidenceKind.ADVISORY_COMPONENT,
                                advisoryExplanation(advisory, componentPath, service, ownerRefs),
                                AffectedProductEvidence.from(match.affectedProduct(), match.identityMatch()),
                                componentPath,
                                null);
                        impacted.computeIfAbsent(service.getId(), ignored ->
                                        new ServiceAccumulator(service, ownerRefs))
                                .evidence.add(evidence);
                    });
        }

        return new ChangeMatchResponse(
                ChangeRef.from(signal), matchedComponentIds.size(), 0, toResponses(impacted));
    }

    private ChangeMatchResponse matchVendorIncident(ChangeSignal signal, VendorIncident incident) {
        List<ThirdPartyVendor> matchedVendors = inventory.findThirdPartyVendorsForMatching().stream()
                .filter(vendor -> vendorMatches(vendor, incident))
                .toList();
        Map<UUID, ServiceAccumulator> impacted = new LinkedHashMap<>();

        for (ThirdPartyVendor vendor : matchedVendors) {
            List<UUID> serviceIds = inventory.findServiceIdsForVendor(vendor.getId());
            if (serviceIds.isEmpty()) {
                continue;
            }
            Map<UUID, BusinessService> services = inventory.findBusinessServicesByIds(serviceIds);
            Map<UUID, List<Owner>> owners = inventory.findOwnersForServices(serviceIds);
            for (UUID serviceId : serviceIds.stream().sorted().toList()) {
                BusinessService service = services.get(serviceId);
                if (service == null) {
                    continue;
                }
                List<OwnerRef> ownerRefs = ownerRefs(owners.get(serviceId));
                EvidenceChain evidence = new EvidenceChain(
                        EvidenceKind.VENDOR_DEPENDENCY,
                        vendorExplanation(incident, vendor, service, ownerRefs),
                        null,
                        List.of(),
                        VendorRef.from(vendor));
                impacted.computeIfAbsent(serviceId, ignored -> new ServiceAccumulator(service, ownerRefs))
                        .evidence.add(evidence);
            }
        }

        return new ChangeMatchResponse(ChangeRef.from(signal), 0, matchedVendors.size(), toResponses(impacted));
    }

    /** Shortest reverse-dependency path from the vulnerable component to every dependent. */
    private Map<UUID, List<UUID>> dependentPaths(UUID rootId) {
        Map<UUID, List<UUID>> paths = new LinkedHashMap<>();
        paths.put(rootId, List.of(rootId));
        Set<UUID> frontier = Set.of(rootId);
        while (!frontier.isEmpty()) {
            Set<UUID> next = new LinkedHashSet<>();
            List<ComponentDependencyLink> links = inventory.findComponentDependencyLinks(frontier).stream()
                    .sorted(Comparator.comparing(ComponentDependencyLink::componentId)
                            .thenComparing(ComponentDependencyLink::dependencyId))
                    .toList();
            for (ComponentDependencyLink link : links) {
                if (paths.containsKey(link.componentId())) {
                    continue;
                }
                List<UUID> prefix = paths.get(link.dependencyId());
                if (prefix == null) {
                    continue;
                }
                List<UUID> path = new ArrayList<>(prefix);
                path.add(link.componentId());
                paths.put(link.componentId(), List.copyOf(path));
                next.add(link.componentId());
            }
            frontier = next;
        }
        return paths;
    }

    private static List<ImpactedServiceMatch> toResponses(Map<UUID, ServiceAccumulator> impacted) {
        return impacted.values().stream()
                .sorted(Comparator.comparing(
                                (ServiceAccumulator value) -> value.service.getName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(value -> value.service.getId()))
                .map(value -> new ImpactedServiceMatch(
                        ServiceRef.from(value.service), value.owners, List.copyOf(value.evidence)))
                .toList();
    }

    private static List<OwnerRef> ownerRefs(List<Owner> owners) {
        if (owners == null) {
            return List.of();
        }
        return owners.stream()
                .sorted(Comparator.comparing(Owner::getName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Owner::getId))
                .map(OwnerRef::from)
                .toList();
    }

    private static boolean vendorMatches(ThirdPartyVendor vendor, VendorIncident incident) {
        String inventoryName = canonical(vendor.getName());
        return inventoryName.equals(canonical(incident.vendorName()))
                || inventoryName.equals(canonical(incident.vendorKey()));
    }

    private static String canonical(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static String advisoryExplanation(
            Advisory advisory,
            List<ComponentRef> componentPath,
            BusinessService service,
            List<OwnerRef> owners) {
        ComponentRef affected = componentPath.getFirst();
        String route = componentPath.stream().map(ComponentRef::name).collect(Collectors.joining(" -> "));
        return "%s affects %s %s; dependency path %s reaches service %s; owners: %s"
                .formatted(
                        advisory.externalId(),
                        affected.name(),
                        affected.version(),
                        route,
                        service.getName(),
                        ownerNames(owners));
    }

    private static String vendorExplanation(
            VendorIncident incident,
            ThirdPartyVendor vendor,
            BusinessService service,
            List<OwnerRef> owners) {
        return "%s affects vendor %s; service %s depends on that vendor; owners: %s"
                .formatted(incident.externalId(), vendor.getName(), service.getName(), ownerNames(owners));
    }

    private static String ownerNames(List<OwnerRef> owners) {
        return owners.isEmpty()
                ? "unassigned"
                : owners.stream().map(OwnerRef::name).collect(Collectors.joining(", "));
    }

    private static final class ServiceAccumulator {
        private final BusinessService service;
        private final List<OwnerRef> owners;
        private final List<EvidenceChain> evidence = new ArrayList<>();

        private ServiceAccumulator(BusinessService service, List<OwnerRef> owners) {
            this.service = service;
            this.owners = owners;
        }
    }
}
