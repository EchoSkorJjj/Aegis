package com.aegis.core.inventory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aegis.core.inventory.DependencyGraph.ServiceGraph;
import com.aegis.core.inventory.DependencyGraph.Use;
import com.aegis.core.inventory.InventoryStore.ComponentDependencyLink;
import com.aegis.core.inventory.InventoryStore.ServiceComponentLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Each service's own dependency graph over an in-memory store (no Docker): edges a service's SBOM declared hold in
 * that service only, edges added by hand in every one, and a walk never mixes two services' resolutions.
 */
class DependencyGraphTest {

    private final InventoryStore store = mock(InventoryStore.class);
    private final DependencyGraph graph = new DependencyGraph(store);

    private final List<ComponentDependencyLink> edges = new ArrayList<>();
    private final List<ServiceComponentLink> links = new ArrayList<>();

    @BeforeEach
    void inMemoryStore() {
        when(store.findComponentDependencyLinks(any())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return edges.stream().filter(edge -> ids.contains(edge.dependencyId())).toList();
        });
        when(store.findDependenciesOf(any(), any())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            Collection<UUID> services = call.getArgument(1);
            return edges.stream().filter(edge -> ids.contains(edge.componentId())
                    && (edge.serviceId() == null || services.contains(edge.serviceId()))).toList();
        });
        when(store.findServiceComponentLinks(any())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return links.stream().filter(link -> ids.contains(link.componentId())).toList();
        });
        when(store.findServiceComponentLinksForServices(any())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return links.stream().filter(link -> ids.contains(link.serviceId())).toList();
        });
    }

    @Test
    void aComponentALinksItselfIsUsedDirectly() {
        UUID lib = UUID.randomUUID();
        UUID service = UUID.randomUUID();
        link(service, lib);

        assertThat(graph.usesOf(List.of(lib)).get(lib)).containsExactly(new Use(service, List.of(lib)));
    }

    @Test
    void eachServiceIsReportedOnlyWithWhatItsOwnLockfileResolved() {
        // Both use http 1.0.0; one lockfile resolved it to redirects 1.0.0, the other to redirects 2.0.0.
        UUID http = UUID.randomUUID();
        UUID oldRedirects = UUID.randomUUID();
        UUID newRedirects = UUID.randomUUID();
        UUID stale = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        link(stale, http);
        link(current, http);
        declares(stale, http, oldRedirects);
        declares(current, http, newRedirects);

        Map<UUID, List<Use>> uses = graph.usesOf(List.of(oldRedirects, newRedirects));

        assertThat(uses.get(oldRedirects)).containsExactly(new Use(stale, List.of(oldRedirects, http)));
        assertThat(uses.get(newRedirects)).containsExactly(new Use(current, List.of(newRedirects, http)));
        Map<UUID, ServiceGraph> graphs = graph.graphsOf(List.of(stale, current));
        assertThat(graphs.get(stale).below(http)).containsExactly(oldRedirects);
        assertThat(graphs.get(current).below(http)).containsExactly(newRedirects);
    }

    @Test
    void anEdgeAddedByHandHoldsInEveryService() {
        UUID framework = UUID.randomUUID();
        UUID lib = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        link(first, framework);
        link(second, framework);
        edges.add(new ComponentDependencyLink(framework, lib));

        assertThat(graph.usesOf(List.of(lib)).get(lib)).extracting(Use::serviceId).containsExactly(
                min(first, second), max(first, second));
        assertThat(graph.graphsOf(List.of(first, second)).values())
                .allSatisfy(serviceGraph -> assertThat(serviceGraph.below(framework)).containsExactly(lib));
    }

    @Test
    void aPathStaysInsideOneServicesGraphEvenWhenAnotherServiceHasAShorterOne() {
        // Both link app; one resolved app → db → driver, the other app → driver directly.
        UUID app = UUID.randomUUID();
        UUID db = UUID.randomUUID();
        UUID driver = UUID.randomUUID();
        UUID layered = UUID.randomUUID();
        UUID flat = UUID.randomUUID();
        link(layered, app);
        link(flat, app);
        declares(layered, app, db);
        declares(layered, db, driver);
        declares(flat, app, driver);

        List<Use> uses = graph.usesOf(List.of(driver)).get(driver);

        assertThat(uses).containsExactlyInAnyOrder(
                new Use(layered, List.of(driver, db, app)),
                new Use(flat, List.of(driver, app)));
    }

    @Test
    void aWalkCombinesEdgesAddedByHandWithTheServicesOwn() {
        UUID app = UUID.randomUUID();
        UUID vendored = UUID.randomUUID();
        UUID lib = UUID.randomUUID();
        UUID service = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        link(service, app);
        link(other, app);
        edges.add(new ComponentDependencyLink(app, vendored));
        declares(service, vendored, lib);

        assertThat(graph.usesOf(List.of(lib)).get(lib)).containsExactly(new Use(service, List.of(lib, vendored, app)));
        assertThat(graph.graphsOf(List.of(service)).get(service).below(app)).containsExactlyInAnyOrder(vendored, lib);
        assertThat(graph.graphsOf(List.of(other)).get(other).below(app)).containsExactly(vendored);
    }

    @Test
    void everyComponentAServiceLinksThatReachesItIsAUse() {
        UUID base = UUID.randomUUID();
        UUID left = UUID.randomUUID();
        UUID top = UUID.randomUUID();
        UUID service = UUID.randomUUID();
        declares(service, left, base);
        declares(service, top, left);
        link(service, left);
        link(service, top);

        assertThat(graph.usesOf(List.of(base)).get(base)).containsExactlyInAnyOrder(
                new Use(service, List.of(base, left)),
                new Use(service, List.of(base, left, top)));
    }

    @Test
    void deepChainsAreWalkedWithOneBatchedLookupPerLevel() {
        UUID previous = UUID.randomUUID();
        UUID vulnerable = previous;
        UUID service = UUID.randomUUID();
        for (int level = 1; level <= 5; level++) {
            UUID next = UUID.randomUUID();
            declares(service, next, previous);
            previous = next;
        }
        UUID top = previous;
        link(service, top);

        assertThat(graph.usesOf(List.of(vulnerable)).get(vulnerable)).singleElement()
                .satisfies(use -> assertThat(use.path()).hasSize(6).startsWith(vulnerable).endsWith(top));
        // Five levels of dependents plus the final empty frontier — never one lookup per component.
        verify(store, times(6)).findComponentDependencyLinks(any());
    }

    @Test
    void cyclesTerminate() {
        UUID target = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID service = UUID.randomUUID();
        declares(service, a, target);
        declares(service, b, a);
        declares(service, a, b);
        declares(service, target, b);
        link(service, b);

        assertThat(graph.usesOf(List.of(target)).get(target)).containsExactly(new Use(service, List.of(target, a, b)));
        assertThat(graph.graphsOf(List.of(service)).get(service).below(b)).containsExactlyInAnyOrder(a, target);
    }

    @Test
    void aboveAComponentIsEverythingThatPullsItInInThatServicesGraphAndACycleEnds() {
        UUID service = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID app = UUID.randomUUID();
        UUID framework = UUID.randomUUID();
        UUID middle = UUID.randomUUID();
        UUID leaf = UUID.randomUUID();
        link(service, app);
        link(service, framework);
        declares(service, app, middle);
        declares(service, framework, middle);
        declares(service, middle, leaf);
        declares(service, leaf, middle); // a cycle
        declares(other, app, leaf);      // another service's resolution, never this one's

        ServiceGraph graph = this.graph.graphsOf(List.of(service)).get(service);

        assertThat(graph.above(leaf)).containsExactlyInAnyOrder(middle, app, framework);
        assertThat(graph.above(middle)).containsExactlyInAnyOrder(leaf, app, framework);
        assertThat(graph.above(app)).isEmpty();
    }

    @Test
    void aComponentNothingUsesHasNoUsesAndAServiceWithoutComponentsAnEmptyGraph() {
        UUID lonely = UUID.randomUUID();
        UUID empty = UUID.randomUUID();

        assertThat(graph.usesOf(List.of(lonely))).containsExactly(Map.entry(lonely, List.of()));
        assertThat(graph.graphsOf(List.of(empty)).get(empty).components()).isEmpty();
        assertThat(graph.usesOf(List.of())).isEmpty();
    }

    private void link(UUID service, UUID component) {
        links.add(new ServiceComponentLink(service, component));
    }

    private void declares(UUID service, UUID component, UUID dependency) {
        edges.add(new ComponentDependencyLink(component, dependency, service));
    }

    private static UUID min(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a : b;
    }

    private static UUID max(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? b : a;
    }
}
