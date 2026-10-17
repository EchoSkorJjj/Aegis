package com.aegis.core.matching;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.incidents.VendorIncident;
import com.aegis.core.incidents.VendorIncidentStore;
import com.aegis.core.inventory.DependencyGraph;
import com.aegis.core.inventory.DependencyGraph.Use;
import com.aegis.core.inventory.InventoryStore;
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
import com.aegis.core.matching.MatchingDtos.IdentityMatch;
import com.aegis.core.matching.MatchingDtos.ImpactedServiceMatch;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.matching.MatchingDtos.OwnerRef;
import com.aegis.core.matching.MatchingDtos.ServiceRef;
import com.aegis.core.matching.MatchingDtos.VendorRef;
import com.aegis.core.matching.MatchingDtos.VersionMatch;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.signals.ChangeSignal;
import com.aegis.core.signals.ChangeSignalStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for Epic 6's deterministic match, traversal, and evidence generation.
 *
 * <p>Matching is recomputed on demand for each request; there is no findings table yet (that is a
 * later epic), so nothing here is persisted or cached.
 */
@Service
public class MatchingService {

    private final ChangeSignalStore signals;
    private final AdvisoryStore advisories;
    private final VendorIncidentStore incidents;
    private final InventoryStore inventory;
    private final DependencyGraph graph;
    private final AdvisoryComponentMatcher componentMatcher;

    public MatchingService(
            ChangeSignalStore signals,
            AdvisoryStore advisories,
            VendorIncidentStore incidents,
            InventoryStore inventory,
            DependencyGraph graph,
            AdvisoryComponentMatcher componentMatcher) {
        this.signals = signals;
        this.advisories = advisories;
        this.incidents = incidents;
        this.inventory = inventory;
        this.graph = graph;
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

    private ChangeMatchResponse matchAdvisory(ChangeSignal signal, Advisory representative) {
        // Expand to every advisory sharing this vulnerability's canonical id (NVD + OSV + …) and match
        // against the union of their affected products, so a CVE is matched by both its CPE and its purl.
        String canonicalId = representative.canonicalId();
        List<AffectedProduct> affected = advisories.findByCanonicalId(canonicalId).stream()
                .flatMap(entry -> entry.affected().stream())
                .distinct()
                .toList();
        List<ComponentMatch> componentMatches = componentMatcher.match(
                affected, inventory.findSoftwareComponentsForMatching());

        // Who uses each matched (vulnerable) component, each service through its own dependency graph: one use per
        // component the service links that reaches it, along the shortest path. Worked out once per component.
        Set<UUID> matchedComponentIds = new LinkedHashSet<>();
        componentMatches.forEach(match -> matchedComponentIds.add(match.component().getId()));
        Map<UUID, List<Use>> uses = graph.usesOf(matchedComponentIds);

        // Every downstream lookup is batched once over all the uses — not per matched component.
        Set<UUID> serviceIds = new LinkedHashSet<>();
        Set<UUID> onPaths = new LinkedHashSet<>();
        uses.values().forEach(found -> found.forEach(use -> {
            serviceIds.add(use.serviceId());
            onPaths.addAll(use.path());
        }));
        if (serviceIds.isEmpty()) {
            return new ChangeMatchResponse(ChangeRef.from(signal), matchedComponentIds.size(), 0, List.of());
        }
        Map<UUID, BusinessService> services = inventory.findBusinessServicesByIds(serviceIds);
        Map<UUID, List<Owner>> owners = inventory.findOwnersForServices(serviceIds);
        Map<UUID, SoftwareComponent> components = inventory.findSoftwareComponentsByIds(onPaths);

        Map<UUID, ServiceAccumulator> impacted = new LinkedHashMap<>();

        for (ComponentMatch match : componentMatches) {
            for (Use use : uses.getOrDefault(match.component().getId(), List.of())) {
                BusinessService service = services.get(use.serviceId());
                if (service == null) {
                    continue;
                }
                List<ComponentRef> componentPath = use.path().stream()
                        .map(components::get)
                        .filter(Objects::nonNull)
                        .map(ComponentRef::from)
                        .toList();
                if (componentPath.isEmpty()) {
                    continue; // never emit evidence we cannot describe
                }
                List<OwnerRef> ownerRefs = ownerRefs(owners.get(use.serviceId()));
                EvidenceChain evidence = new EvidenceChain(
                        EvidenceKind.ADVISORY_COMPONENT,
                        match.confidence(),
                        advisoryExplanation(canonicalId, match, componentPath, service, ownerRefs),
                        AffectedProductEvidence.from(
                                match.affectedProduct(), match.identityMatch(), match.versionMatch()),
                        componentPath,
                        null);
                impacted.computeIfAbsent(service.getId(), ignored -> new ServiceAccumulator(service, ownerRefs))
                        .evidence.add(evidence);
            }
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
                        // A vendor link is a direct, exact name/key equality — no inference.
                        MatchConfidence.CONFIRMED,
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

    private static List<ImpactedServiceMatch> toResponses(Map<UUID, ServiceAccumulator> impacted) {
        return impacted.values().stream()
                .sorted(Comparator.comparing(
                                (ServiceAccumulator value) -> value.service.getName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(value -> value.service.getId()))
                .map(value -> new ImpactedServiceMatch(
                        ServiceRef.from(value.service),
                        aggregateConfidence(value.evidence),
                        value.owners,
                        List.copyOf(value.evidence)))
                .toList();
    }

    /** A service is CONFIRMED impacted if any one reason is confirmed; otherwise it is a review item. */
    private static MatchConfidence aggregateConfidence(List<EvidenceChain> evidence) {
        return evidence.stream().anyMatch(chain -> chain.confidence() == MatchConfidence.CONFIRMED)
                ? MatchConfidence.CONFIRMED
                : MatchConfidence.POSSIBLE;
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
        String inventoryName = normalizeVendor(vendor.getName());
        return inventoryName.equals(normalizeVendor(incident.vendorName()))
                || inventoryName.equals(normalizeVendor(incident.vendorKey()));
    }

    /** Vendor identity key: lower-cased, all non-alphanumerics removed (so "Acme Cloud" == "acme-cloud"). */
    private static String normalizeVendor(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static String advisoryExplanation(
            String vulnerabilityId,
            ComponentMatch match,
            List<ComponentRef> componentPath,
            BusinessService service,
            List<OwnerRef> owners) {
        ComponentRef affected = componentPath.getFirst();
        String route = componentPath.stream().map(ComponentRef::name).collect(Collectors.joining(" -> "));
        String base = "%s affects %s %s; dependency path %s reaches service %s; owners: %s".formatted(
                vulnerabilityId,
                affected.name(),
                affected.version(),
                route,
                service.getName(),
                ownerNames(owners));
        return match.confidence() == MatchConfidence.POSSIBLE
                ? base + " (possible match — " + possibleReason(match) + ", review before acting)"
                : base;
    }

    private static String possibleReason(ComponentMatch match) {
        if (match.versionMatch() == VersionMatch.UNDETERMINED) {
            return "component version could not be range-checked";
        }
        return match.identityMatch() == IdentityMatch.CPE_VENDOR_PRODUCT_ALIAS
                ? "matched via a CPE product alias"
                : "matched on an ambiguous package name";
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
