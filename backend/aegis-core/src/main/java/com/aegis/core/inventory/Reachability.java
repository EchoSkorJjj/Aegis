package com.aegis.core.inventory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.aegis.core.inventory.CodeUsage.Status;
import com.aegis.core.inventory.CodeUsage.Usage;
import com.aegis.core.inventory.DependencyGraph.ServiceGraph;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.SoftwareComponent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether a service's code reaches a package (E11-4), from where the code imports packages ({@link CodeUsage}) and
 * the service's own dependency graph ({@link DependencyGraph}). {@link Status#IMPORTED} when the code imports the
 * package itself. A package another one pulls in is reached through that one, so it is {@link Status#NOT_IMPORTED}
 * only when neither it nor anything above it in the graph is imported — each of them not imported by CodeUsage's own
 * rule of evidence — and {@link Status#UNKNOWN} while the code imports what pulls it in, or that is unknown. Scoring
 * discounts only not imported: unknown scores like imported, so no score drops without evidence.
 */
@Service
public class Reachability {

    /** How many of the packages pulling one in a reason names before "and N more". */
    private static final int NAMED = 3;

    private final InventoryStore store;
    private final DependencyGraph graph;
    private final CodeUsage codeUsage;

    public Reachability(InventoryStore store, DependencyGraph graph, CodeUsage codeUsage) {
        this.store = store;
        this.graph = graph;
        this.codeUsage = codeUsage;
    }

    /**
     * Whether the service's code reaches a package, and why. {@code through} names the package pulling it in that the
     * verdict rests on — one the code imports, or one whose imports are unknown — and is null when it rests on the
     * package itself; {@code usage} is that package's imports, with the lines (null when the code wasn't read for it).
     */
    public record Verdict(Status status, String reason, String through, Usage usage) {

        /** Stronger evidence of reach first: imported, then unknown, then not imported. */
        static final Comparator<Verdict> MOST_REACHABLE = Comparator.comparingInt(verdict -> switch (verdict.status()) {
            case IMPORTED -> 0;
            case UNKNOWN -> 1;
            case NOT_IMPORTED -> 2;
        });
    }

    /** For each service, whether its code reaches each of these components (ids by service). */
    @Transactional(readOnly = true)
    public Reached of(Map<UUID, ? extends Collection<UUID>> componentIdsByService) {
        if (componentIdsByService.isEmpty()) {
            return Reached.NONE;
        }
        Map<UUID, BusinessService> services = store.findBusinessServicesByIds(componentIdsByService.keySet());
        return of(services, graph.graphsOf(services.keySet()), componentIdsByService);
    }

    /**
     * The same, for services and graphs the caller has already loaded ({@link DependencyGraph#graphsOf}): one lookup
     * for the components and their packages, and one for the code, however many there are.
     */
    @Transactional(readOnly = true)
    public Reached of(Map<UUID, BusinessService> services, Map<UUID, ServiceGraph> graphs,
            Map<UUID, ? extends Collection<UUID>> componentIdsByService) {
        Set<UUID> ids = new LinkedHashSet<>();
        componentIdsByService.forEach((serviceId, componentIds) -> {
            ServiceGraph serviceGraph = graphs.get(serviceId);
            if (serviceGraph != null && services.containsKey(serviceId)) {
                componentIds.forEach(id -> {
                    ids.add(id);
                    ids.addAll(serviceGraph.above(id));
                });
            }
        });
        if (ids.isEmpty()) {
            return Reached.NONE;
        }
        Map<UUID, SoftwareComponent> components = store.findSoftwareComponentsByIds(ids);
        List<CodeUsage.Package> packages = components.values().stream()
                .map(component -> new CodeUsage.Package(component.getEcosystem(), component.getName()))
                .distinct().toList();
        return new Reached(graphs, components, codeUsage.read(services, packages));
    }

    /** What {@link #of} read: each service's graph, the components in it, and the code. */
    public static final class Reached {

        /** Nothing read: every verdict is unknown. */
        public static final Reached NONE = new Reached(Map.of(), Map.of(), null);

        private static final Verdict UNREAD = new Verdict(Status.UNKNOWN,
                "Aegis hasn't read whether the service's code imports it", null, null);

        private final Map<UUID, ServiceGraph> graphs;
        private final Map<UUID, SoftwareComponent> components;
        private final CodeUsage.Code code;

        Reached(Map<UUID, ServiceGraph> graphs, Map<UUID, SoftwareComponent> components, CodeUsage.Code code) {
            this.graphs = graphs;
            this.components = components;
            this.code = code;
        }

        /** Whether the service's code reaches the component, and why. */
        public Verdict verdict(UUID serviceId, UUID componentId) {
            ServiceGraph serviceGraph = graphs.get(serviceId);
            SoftwareComponent component = components.get(componentId);
            if (serviceGraph == null || component == null || code == null) {
                return UNREAD;
            }
            boolean fromRepository = fromRepository(serviceGraph, componentId);
            Map<String, Usage> above = new LinkedHashMap<>();
            serviceGraph.above(componentId).stream().map(components::get).filter(Objects::nonNull)
                    .sorted(Comparator.comparing(SoftwareComponent::getName, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(SoftwareComponent::getName))
                    .forEach(parent -> above.putIfAbsent(parent.getName(), code.usageOf(serviceId,
                            parent.getEcosystem(), parent.getName(), fromRepository)));
            above.remove(component.getName());
            return Reachability.verdict(component.getName(),
                    code.usageOf(serviceId, component.getEcosystem(), component.getName(), fromRepository), above);
        }

        /**
         * Whether the service's code reaches a change that matched several of its components: the most reachable of
         * them — imported if any one is, not imported only if none can be reached.
         */
        public Verdict strongest(UUID serviceId, Collection<UUID> componentIds) {
            return componentIds.stream().map(id -> verdict(serviceId, id))
                    .min(Verdict.MOST_REACHABLE.thenComparing(verdict -> Objects.requireNonNullElse(verdict.reason(),
                            "")))
                    .orElse(UNREAD);
        }

        /** Where the service's code imports the component itself (null when it wasn't read). */
        public Usage usage(UUID serviceId, UUID componentId) {
            ServiceGraph serviceGraph = graphs.get(serviceId);
            SoftwareComponent component = components.get(componentId);
            if (serviceGraph == null || component == null || code == null) {
                return null;
            }
            return code.usageOf(serviceId, component.getEcosystem(), component.getName(),
                    fromRepository(serviceGraph, componentId));
        }
    }

    /**
     * The verdict for package {@code name}, from where the code imports it and each package above it ({@code above},
     * by name): imported itself; else unknown, through the first package above it the code imports; else unknown when
     * its own imports are; else unknown, through the first package above it whose imports are; else not imported.
     */
    static Verdict verdict(String name, Usage own, Map<String, Usage> above) {
        if (own.status() == Status.IMPORTED) {
            return new Verdict(Status.IMPORTED, null, null, own);
        }
        for (Map.Entry<String, Usage> parent : above.entrySet()) {
            if (parent.getValue().status() == Status.IMPORTED) {
                return new Verdict(Status.UNKNOWN, (own.status() == Status.NOT_IMPORTED
                        ? name + " isn't imported itself, but it comes in through "
                        : name + " comes in through ") + parent.getKey() + ", which the code imports",
                        parent.getKey(), parent.getValue());
            }
        }
        if (own.status() == Status.UNKNOWN) {
            return new Verdict(Status.UNKNOWN, own.reason(), null, own);
        }
        for (Map.Entry<String, Usage> parent : above.entrySet()) {
            if (parent.getValue().status() == Status.UNKNOWN) {
                return new Verdict(Status.UNKNOWN, "Nothing imports " + name + " itself, but it comes in through "
                        + parent.getKey() + ", and whether the code imports that is unknown: "
                        + lowercaseFirst(parent.getValue().reason()), parent.getKey(), parent.getValue());
            }
        }
        if (above.isEmpty()) {
            return new Verdict(Status.NOT_IMPORTED, own.reason(), null, own);
        }
        List<String> names = new ArrayList<>(above.keySet());
        String named = names.size() <= NAMED ? join(names)
                : String.join(", ", names.subList(0, NAMED)) + " and " + (names.size() - NAMED) + " more";
        return new Verdict(Status.NOT_IMPORTED, own.reason() + ", nor " + named + ", which "
                + (names.size() == 1 ? "pulls" : "pull") + " it in", null, own);
    }

    /**
     * Whether an import from the repository linked the component, or a component the service links that pulls it in:
     * what the code read can speak for.
     */
    private static boolean fromRepository(ServiceGraph serviceGraph, UUID componentId) {
        Set<UUID> above = serviceGraph.above(componentId);
        return serviceGraph.direct().stream().anyMatch(link -> link.fromRepository()
                && (link.componentId().equals(componentId) || above.contains(link.componentId())));
    }

    private static String join(List<String> names) {
        return names.size() == 1 ? names.getFirst()
                : String.join(", ", names.subList(0, names.size() - 1)) + " or " + names.getLast();
    }

    private static String lowercaseFirst(String text) {
        return text == null || text.isEmpty() ? "" : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }
}
