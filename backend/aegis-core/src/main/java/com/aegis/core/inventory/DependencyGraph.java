package com.aegis.core.inventory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.aegis.core.inventory.InventoryStore.ComponentDependencyLink;
import com.aegis.core.inventory.InventoryStore.ServiceComponentLink;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dependency graph as each service has it — the one walk behind a change's impact (matching), a service's
 * vulnerable dependencies (the recommendations) and "Used by". A service's graph is the components it links directly,
 * the dependency edges its own SBOMs declared, and the edges added by hand, which hold in every service. Another
 * service's lockfile may resolve the same library version to different dependencies; that resolution is never part of
 * this service's graph. Each walk makes one lookup per level, however many components or services it covers.
 */
@Service
public class DependencyGraph {

    /** Bound on a walk; real dependency graphs are far shallower. */
    public static final int MAX_DEPTH = 32;

    private static final Comparator<ComponentDependencyLink> EDGE_ORDER = Comparator
            .comparing(ComponentDependencyLink::componentId)
            .thenComparing(ComponentDependencyLink::dependencyId)
            .thenComparing(ComponentDependencyLink::serviceId, Comparator.nullsFirst(Comparator.naturalOrder()));
    private static final Comparator<ServiceComponentLink> LINK_ORDER = Comparator
            .comparing(ServiceComponentLink::serviceId)
            .thenComparing(ServiceComponentLink::componentId);

    private final InventoryStore store;

    public DependencyGraph(InventoryStore store) {
        this.store = store;
    }

    /**
     * A service's use of a component: through {@code path}, from the component up to the one the service links
     * directly — {@code [component, …, linked]}, a single element when it links the component itself.
     */
    public record Use(UUID serviceId, List<UUID> path) {

        public Use {
            path = List.copyOf(path);
        }

        public boolean direct() {
            return path.size() == 1;
        }

        /** The component the service links directly. */
        public UUID linkedComponentId() {
            return path.getLast();
        }
    }

    /**
     * Who uses each of {@code componentIds}: a {@link Use} for every component a service links that reaches it in that
     * service's graph, along the shortest path there. Components nothing uses map to an empty list.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<Use>> usesOf(Collection<UUID> componentIds) {
        Map<UUID, List<Use>> uses = new LinkedHashMap<>();
        componentIds.forEach(id -> uses.putIfAbsent(id, new ArrayList<>()));
        if (uses.isEmpty()) {
            return uses;
        }
        // Walk up from each component. A step is where a walk has got to, and whose graph it is in: every service's
        // while each edge on the way was added by hand, else the one service whose SBOM declared an edge on it.
        Map<Step, List<UUID>> paths = new LinkedHashMap<>();
        List<Step> frontier = new ArrayList<>();
        for (UUID root : uses.keySet()) {
            Step start = new Step(root, null, root);
            paths.put(start, List.of(root));
            frontier.add(start);
        }
        for (int depth = 0; depth < MAX_DEPTH && !frontier.isEmpty(); depth++) {
            Map<UUID, List<Step>> at = new LinkedHashMap<>();
            frontier.forEach(step -> at.computeIfAbsent(step.component(), component -> new ArrayList<>()).add(step));
            List<ComponentDependencyLink> edges = store.findComponentDependencyLinks(at.keySet()).stream()
                    .sorted(EDGE_ORDER)
                    .toList();
            List<Step> next = new ArrayList<>();
            for (ComponentDependencyLink edge : edges) {
                for (Step step : at.getOrDefault(edge.dependencyId(), List.of())) {
                    if (edge.serviceId() != null && step.service() != null && !edge.serviceId().equals(step.service())) {
                        continue; // another service's resolution
                    }
                    UUID service = edge.serviceId() != null ? edge.serviceId() : step.service();
                    Step up = new Step(step.root(), service, edge.componentId());
                    // Reached already — or, for one service, by a walk no longer that holds in every service.
                    if (paths.containsKey(up)
                            || (service != null && paths.containsKey(new Step(step.root(), null, edge.componentId())))) {
                        continue;
                    }
                    List<UUID> path = new ArrayList<>(paths.get(step));
                    path.add(edge.componentId());
                    paths.put(up, List.copyOf(path));
                    next.add(up);
                }
            }
            frontier = next;
        }

        Set<UUID> reached = new LinkedHashSet<>();
        paths.keySet().forEach(step -> reached.add(step.component()));
        List<ServiceComponentLink> links = store.findServiceComponentLinks(reached).stream().sorted(LINK_ORDER).toList();
        uses.forEach((root, found) -> {
            for (ServiceComponentLink link : links) {
                List<UUID> own = paths.get(new Step(root, link.serviceId(), link.componentId()));
                List<UUID> everywhere = paths.get(new Step(root, null, link.componentId()));
                List<UUID> path = own == null ? everywhere
                        : everywhere == null || own.size() < everywhere.size() ? own : everywhere;
                if (path != null) {
                    found.add(new Use(link.serviceId(), path));
                }
            }
        });
        return uses;
    }

    /** Where a walk up from {@code root} has got to, in {@code service}'s graph (null: in every service's). */
    private record Step(UUID root, UUID service, UUID component) {
    }

    /** Each of these services' graphs, walked down from the components it links. */
    @Transactional(readOnly = true)
    public Map<UUID, ServiceGraph> graphsOf(Collection<UUID> serviceIds) {
        Map<UUID, List<ServiceComponentLink>> direct = new LinkedHashMap<>();
        serviceIds.forEach(id -> direct.putIfAbsent(id, new ArrayList<>()));
        if (direct.isEmpty()) {
            return Map.of();
        }
        store.findServiceComponentLinksForServices(direct.keySet()).forEach(link ->
                direct.computeIfAbsent(link.serviceId(), id -> new ArrayList<>()).add(link));

        Map<UUID, Map<UUID, Set<UUID>>> dependsOn = new HashMap<>();
        Map<UUID, Set<UUID>> seen = new HashMap<>();
        Map<UUID, Set<UUID>> frontier = new LinkedHashMap<>();
        direct.forEach((service, links) -> links.forEach(link -> {
            if (seen.computeIfAbsent(service, id -> new LinkedHashSet<>()).add(link.componentId())) {
                frontier.computeIfAbsent(service, id -> new LinkedHashSet<>()).add(link.componentId());
            }
        }));
        for (int depth = 0; depth < MAX_DEPTH && !frontier.isEmpty(); depth++) {
            Set<UUID> components = new LinkedHashSet<>();
            frontier.values().forEach(components::addAll);
            Map<UUID, List<ComponentDependencyLink>> out = new HashMap<>();
            store.findDependenciesOf(components, Set.copyOf(frontier.keySet())).stream().sorted(EDGE_ORDER).forEach(edge ->
                    out.computeIfAbsent(edge.componentId(), id -> new ArrayList<>()).add(edge));
            Map<UUID, Set<UUID>> next = new LinkedHashMap<>();
            frontier.forEach((service, at) -> {
                for (UUID component : at) {
                    for (ComponentDependencyLink edge : out.getOrDefault(component, List.of())) {
                        if (edge.serviceId() != null && !edge.serviceId().equals(service)) {
                            continue;
                        }
                        dependsOn.computeIfAbsent(service, id -> new HashMap<>())
                                .computeIfAbsent(component, id -> new LinkedHashSet<>()).add(edge.dependencyId());
                        if (seen.get(service).add(edge.dependencyId())) {
                            next.computeIfAbsent(service, id -> new LinkedHashSet<>()).add(edge.dependencyId());
                        }
                    }
                }
            });
            frontier.clear();
            frontier.putAll(next);
        }

        Map<UUID, ServiceGraph> graphs = new LinkedHashMap<>();
        direct.forEach((service, links) -> graphs.put(service, new ServiceGraph(
                links, dependsOn.getOrDefault(service, Map.of()), seen.getOrDefault(service, Set.of()))));
        return graphs;
    }

    /** One service's graph: the components it links, and what each component depends on in it. */
    public static final class ServiceGraph {

        private final List<ServiceComponentLink> direct;
        private final Map<UUID, Set<UUID>> dependsOn;
        private final Set<UUID> components;
        private final Map<UUID, Set<UUID>> below = new HashMap<>();
        private final Map<UUID, Set<UUID>> above = new HashMap<>();
        private Map<UUID, Set<UUID>> dependedOnBy;

        ServiceGraph(List<ServiceComponentLink> direct, Map<UUID, Set<UUID>> dependsOn, Set<UUID> components) {
            this.direct = List.copyOf(direct);
            this.dependsOn = dependsOn;
            this.components = Set.copyOf(components);
        }

        /** The components the service links directly. */
        public List<ServiceComponentLink> direct() {
            return direct;
        }

        /** Every component in the graph: those linked and all they reach. */
        public Set<UUID> components() {
            return components;
        }

        /** Everything below {@code componentId} in this graph, worked out once per component. */
        public Set<UUID> below(UUID componentId) {
            Set<UUID> cached = below.get(componentId);
            if (cached != null) {
                return cached;
            }
            Set<UUID> reached = new LinkedHashSet<>();
            List<UUID> pending = new ArrayList<>(dependsOn.getOrDefault(componentId, Set.of()));
            while (!pending.isEmpty()) {
                UUID next = pending.removeLast();
                if (!next.equals(componentId) && reached.add(next)) {
                    pending.addAll(dependsOn.getOrDefault(next, Set.of()));
                }
            }
            below.put(componentId, reached);
            return reached;
        }

        /**
         * Everything above {@code componentId} in this graph: every component that pulls it in, directly or through
         * others, up to the ones the service links. Never itself, even in a cycle. Worked out once per component.
         */
        public Set<UUID> above(UUID componentId) {
            Set<UUID> cached = above.get(componentId);
            if (cached != null) {
                return cached;
            }
            if (dependedOnBy == null) {
                dependedOnBy = new HashMap<>();
                dependsOn.forEach((component, dependencies) -> dependencies.forEach(dependency ->
                        dependedOnBy.computeIfAbsent(dependency, id -> new LinkedHashSet<>()).add(component)));
            }
            Set<UUID> reached = new LinkedHashSet<>();
            List<UUID> pending = new ArrayList<>(dependedOnBy.getOrDefault(componentId, Set.of()));
            while (!pending.isEmpty()) {
                UUID next = pending.removeLast();
                if (!next.equals(componentId) && reached.add(next)) {
                    pending.addAll(dependedOnBy.getOrDefault(next, Set.of()));
                }
            }
            above.put(componentId, reached);
            return reached;
        }
    }
}
