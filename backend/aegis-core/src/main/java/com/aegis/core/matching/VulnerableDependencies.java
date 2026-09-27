package com.aegis.core.matching;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.inventory.DependencyGraph;
import com.aegis.core.inventory.DependencyGraph.ServiceGraph;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.InventoryStore.ServiceComponentLink;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.matching.AdvisoryComponentMatcher.ComponentMatch;
import com.aegis.core.matching.MatchingDtos.ComponentRef;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.matching.MatchingDtos.VulnerabilityRef;
import com.aegis.core.matching.MatchingDtos.VulnerableDependency;
import com.aegis.core.signals.ChangeSignalStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every vulnerable dependency of the given services, found from the services' side — nobody has to know
 * which advisory to look up. It walks each service's own dependency graph ({@link DependencyGraph}) from the
 * components the service uses directly, asks the advisory store which advisories name any of those packages
 * (the same prefilter the upgrade target uses), and applies matching's own identity and version rules
 * ({@link AdvisoryComponentMatcher}), so a dependency is listed here exactly when that advisory's impact
 * page would list the service. Read-only and recomputed per request, like the rest of matching.
 */
@Service
public class VulnerableDependencies {

    /** Package names per advisory-store query, keeping each IN list well within bind-parameter limits. */
    private static final int NAMES_PER_QUERY = 250;

    private final InventoryStore inventory;
    private final DependencyGraph graph;
    private final AdvisoryStore advisories;
    private final ChangeSignalStore signals;
    private final AdvisoryComponentMatcher matcher;

    public VulnerableDependencies(InventoryStore inventory, DependencyGraph graph, AdvisoryStore advisories,
            ChangeSignalStore signals, AdvisoryComponentMatcher matcher) {
        this.inventory = inventory;
        this.graph = graph;
        this.advisories = advisories;
        this.signals = signals;
        this.matcher = matcher;
    }

    /**
     * What {@link #find} found: the vulnerable dependencies, and the advisories behind them — every advisory
     * naming one of the packages, and every advisory of each vulnerability found — so working out an upgrade
     * target for each ({@link UpgradeTargets#resolve(String, ComponentRef, Collection)}) needs no lookup.
     */
    public static final class Found {

        static final Found NONE = new Found(List.of(), List.of(), List.of());

        private final List<VulnerableDependency> dependencies;
        // Keyed exactly as the advisory store's prefilter compares them (UpgradeTargets#packageKeys/productKeys).
        private final Map<String, List<Advisory>> byPackageKey = new HashMap<>();
        private final Map<String, List<Advisory>> byProductKey = new HashMap<>();
        private final Map<String, List<Advisory>> byVulnerability = new HashMap<>();

        Found(List<VulnerableDependency> dependencies, Collection<Advisory> namingPackages,
                Collection<Advisory> ofVulnerabilities) {
            this.dependencies = List.copyOf(dependencies);
            for (Advisory advisory : namingPackages) {
                for (AffectedProduct product : advisory.affected()) {
                    if (product.packageName() != null) {
                        UpgradeTargets.packageKeys(product.packageName()).forEach(key ->
                                byPackageKey.computeIfAbsent(key, k -> new ArrayList<>()).add(advisory));
                    }
                    if (product.product() != null) {
                        byProductKey.computeIfAbsent(AdvisoryComponentMatcher.canonical(product.product()),
                                k -> new ArrayList<>()).add(advisory);
                    }
                }
            }
            ofVulnerabilities.forEach(advisory ->
                    byVulnerability.computeIfAbsent(advisory.canonicalId(), id -> new ArrayList<>()).add(advisory));
        }

        public List<VulnerableDependency> dependencies() {
            return dependencies;
        }

        /**
         * What {@link UpgradeTargets#resolve(UUID, ComponentRef)} would load for this component and
         * vulnerability: the vulnerability's own advisories, then every advisory naming the package.
         */
        public List<Advisory> known(ComponentRef component, String vulnerabilityId) {
            Map<UUID, Advisory> known = new LinkedHashMap<>();
            byVulnerability.getOrDefault(vulnerabilityId, List.of())
                    .forEach(advisory -> known.putIfAbsent(advisory.id(), advisory));
            UpgradeTargets.packageKeys(component.name()).forEach(key -> byPackageKey.getOrDefault(key, List.of())
                    .forEach(advisory -> known.putIfAbsent(advisory.id(), advisory)));
            UpgradeTargets.productKeys(component.name()).forEach(key -> byProductKey.getOrDefault(key, List.of())
                    .forEach(advisory -> known.putIfAbsent(advisory.id(), advisory)));
            return List.copyOf(known.values());
        }
    }

    /**
     * Every vulnerable dependency of these services, in a fixed number of batched lookups however many
     * services, dependencies and vulnerabilities there are.
     */
    @Transactional(readOnly = true)
    public Found find(Collection<UUID> serviceIds) {
        if (serviceIds.isEmpty()) {
            return Found.NONE;
        }
        Map<UUID, ServiceGraph> graphs = graph.graphsOf(serviceIds);
        Set<UUID> reachable = new LinkedHashSet<>();
        graphs.values().forEach(serviceGraph -> reachable.addAll(serviceGraph.components()));
        if (reachable.isEmpty()) {
            return Found.NONE;
        }
        Map<UUID, SoftwareComponent> components = inventory.findSoftwareComponentsByIds(reachable);

        Matched matched = vulnerabilities(components.values());
        Map<UUID, List<VulnerabilityRef>> vulnerable = matched.byComponent();
        if (vulnerable.isEmpty()) {
            return Found.NONE;
        }

        List<VulnerableDependency> found = new ArrayList<>();
        for (UUID serviceId : graphs.keySet().stream().sorted().toList()) {
            ServiceGraph serviceGraph = graphs.get(serviceId);
            List<ServiceComponentLink> links = serviceGraph.direct().stream()
                    .sorted(Comparator.comparing((ServiceComponentLink link) -> nameOf(components.get(link.componentId())))
                            .thenComparing(ServiceComponentLink::componentId))
                    .toList();
            Set<UUID> usedDirectly = new HashSet<>();
            links.forEach(link -> usedDirectly.add(link.componentId()));
            Set<UUID> reported = new HashSet<>();
            for (ServiceComponentLink link : links) {
                SoftwareComponent component = components.get(link.componentId());
                if (component != null && vulnerable.containsKey(component.getId()) && reported.add(component.getId())) {
                    found.add(dependency(link, component, null, vulnerable));
                }
            }
            // A transitive one is reported once, through the first direct dependency (by name) that pulls it in.
            for (ServiceComponentLink link : links) {
                SoftwareComponent via = components.get(link.componentId());
                if (via == null) {
                    continue;
                }
                for (UUID id : serviceGraph.below(link.componentId())) {
                    SoftwareComponent component = components.get(id);
                    if (component != null && vulnerable.containsKey(id) && !usedDirectly.contains(id)
                            && reported.add(id)) {
                        found.add(dependency(link, component, via, vulnerable));
                    }
                }
            }
        }
        Set<String> vulnerabilityIds = new HashSet<>();
        found.forEach(dependency -> dependency.vulnerabilities().forEach(ref -> vulnerabilityIds.add(ref.id())));
        return new Found(found, matched.naming(), advisories.findByCanonicalIds(vulnerabilityIds));
    }

    /** The vulnerabilities affecting each component, and every advisory the prefilter loaded to find them. */
    private record Matched(Map<UUID, List<VulnerabilityRef>> byComponent, List<Advisory> naming) {
    }

    private static VulnerableDependency dependency(ServiceComponentLink link, SoftwareComponent component,
            SoftwareComponent via, Map<UUID, List<VulnerabilityRef>> vulnerable) {
        List<VulnerabilityRef> vulnerabilities = vulnerable.get(component.getId());
        MatchConfidence confidence = vulnerabilities.stream()
                .anyMatch(vulnerability -> vulnerability.confidence() == MatchConfidence.CONFIRMED)
                ? MatchConfidence.CONFIRMED : MatchConfidence.POSSIBLE;
        return new VulnerableDependency(link.serviceId(), ComponentRef.from(component),
                via == null ? null : ComponentRef.from(via), link.manifestPaths(), confidence, vulnerabilities);
    }

    /**
     * The vulnerabilities affecting each component, most severe first: every advisory naming one of the
     * packages, grouped by vulnerability (its canonical id, across feeds), matched under matching's rules.
     */
    private Matched vulnerabilities(Collection<SoftwareComponent> components) {
        Map<String, List<SoftwareComponent>> byPackageKey = new HashMap<>();
        Map<String, List<SoftwareComponent>> byProductKey = new HashMap<>();
        for (SoftwareComponent component : components) {
            UpgradeTargets.packageKeys(component.getName())
                    .forEach(key -> byPackageKey.computeIfAbsent(key, k -> new ArrayList<>()).add(component));
            UpgradeTargets.productKeys(component.getName())
                    .forEach(key -> byProductKey.computeIfAbsent(key, k -> new ArrayList<>()).add(component));
        }

        Map<String, List<Advisory>> byVulnerability = new LinkedHashMap<>();
        List<String> names = components.stream().map(SoftwareComponent::getName).distinct().toList();
        Set<UUID> seenAdvisories = new HashSet<>();
        List<Advisory> naming = new ArrayList<>();
        for (int start = 0; start < names.size(); start += NAMES_PER_QUERY) {
            Set<String> packageKeys = new HashSet<>();
            Set<String> productKeys = new HashSet<>();
            for (String name : names.subList(start, Math.min(names.size(), start + NAMES_PER_QUERY))) {
                packageKeys.addAll(UpgradeTargets.packageKeys(name));
                productKeys.addAll(UpgradeTargets.productKeys(name));
            }
            for (Advisory advisory : advisories.findNamingPackage(packageKeys, productKeys)) {
                if (advisory.canonicalId() != null && seenAdvisories.add(advisory.id())) {
                    naming.add(advisory);
                    byVulnerability.computeIfAbsent(advisory.canonicalId(), id -> new ArrayList<>()).add(advisory);
                }
            }
        }

        Map<UUID, Map<String, MatchConfidence>> matched = new HashMap<>();
        for (Map.Entry<String, List<Advisory>> vulnerability : byVulnerability.entrySet()) {
            List<AffectedProduct> affected = vulnerability.getValue().stream()
                    .flatMap(advisory -> advisory.affected().stream()).distinct().toList();
            for (AffectedProduct product : affected) {
                Set<SoftwareComponent> candidates = new LinkedHashSet<>();
                if (product.packageName() != null) {
                    UpgradeTargets.packageKeys(product.packageName()).forEach(key ->
                            candidates.addAll(byPackageKey.getOrDefault(key, List.of())));
                }
                if (product.product() != null) {
                    candidates.addAll(byProductKey.getOrDefault(AdvisoryComponentMatcher.canonical(product.product()),
                            List.of()));
                }
                if (candidates.isEmpty()) {
                    continue;
                }
                for (ComponentMatch match : matcher.match(List.of(product), List.copyOf(candidates))) {
                    matched.computeIfAbsent(match.component().getId(), id -> new HashMap<>())
                            .merge(vulnerability.getKey(), match.confidence(), VulnerableDependencies::stronger);
                }
            }
        }
        if (matched.isEmpty()) {
            return new Matched(Map.of(), naming);
        }

        Set<String> ids = new HashSet<>();
        matched.values().forEach(byId -> ids.addAll(byId.keySet()));
        Map<String, UUID> signalIds = signals.findVulnerabilityIds(ids);
        Map<UUID, List<VulnerabilityRef>> result = new HashMap<>();
        matched.forEach((componentId, byId) -> result.put(componentId, byId.entrySet().stream()
                .map(entry -> reference(entry.getKey(), signalIds.get(entry.getKey()), entry.getValue(),
                        byVulnerability.get(entry.getKey())))
                .sorted(MOST_SEVERE_FIRST)
                .toList()));
        return new Matched(result, naming);
    }

    /** The feeds' own severity: the highest CVSS base score any of them published, else a severity word. */
    private static VulnerabilityRef reference(String id, UUID changeSignalId, MatchConfidence confidence,
            List<Advisory> advisories) {
        Advisory.Cvss scored = advisories.stream().map(Advisory::cvss)
                .filter(cvss -> cvss != null && cvss.baseScore() != null)
                .max(Comparator.comparing(Advisory.Cvss::baseScore)).orElse(null);
        String label = scored != null ? scored.severity() : advisories.stream().map(Advisory::cvss)
                .filter(cvss -> cvss != null && cvss.severity() != null && !cvss.severity().isBlank())
                .map(Advisory.Cvss::severity).findFirst().orElse(null);
        return new VulnerabilityRef(id, changeSignalId, confidence,
                scored == null ? null : scored.version(), scored == null ? null : scored.baseScore(), label);
    }

    private static final Comparator<VulnerabilityRef> MOST_SEVERE_FIRST = Comparator
            .comparing((VulnerabilityRef vulnerability) -> vulnerability.cvssBaseScore() == null
                    ? BigDecimal.valueOf(-1) : vulnerability.cvssBaseScore(), Comparator.reverseOrder())
            .thenComparing(vulnerability -> vulnerability.confidence() != MatchConfidence.CONFIRMED)
            .thenComparing(VulnerabilityRef::id);

    private static MatchConfidence stronger(MatchConfidence left, MatchConfidence right) {
        return left == MatchConfidence.CONFIRMED || right == MatchConfidence.CONFIRMED
                ? MatchConfidence.CONFIRMED : MatchConfidence.POSSIBLE;
    }

    private static String nameOf(SoftwareComponent component) {
        return component == null ? "" : Objects.requireNonNullElse(component.getName(), "");
    }
}
