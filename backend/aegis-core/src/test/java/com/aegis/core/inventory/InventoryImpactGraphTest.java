package com.aegis.core.inventory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.aegis.core.inventory.InventoryDtos.ComponentImpactResponse;
import com.aegis.core.inventory.InventoryDtos.ComponentImpactedServiceResponse;
import com.aegis.core.inventory.InventoryDtos.VendorImpactResponse;
import com.aegis.core.inventory.InventoryStore.ServiceComponentLink;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import com.aegis.core.platform.common.ApiExceptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Impact traversal over an in-memory map: chains, diamonds, cycles, and result shaping (no Docker). */
class InventoryImpactGraphTest {

    private final InventoryStore store = mock(InventoryStore.class);
    // The impact graph never looks anyone up; an empty directory keeps the service honest about that.
    private final InventoryService inventory = new InventoryService(store, userId -> Optional.empty());

    private final Map<UUID, SoftwareComponent> components = new HashMap<>();
    private final Map<UUID, BusinessService> services = new HashMap<>();
    /** [dependent, dependency] pairs, in insertion order. */
    private final List<UUID[]> dependencies = new ArrayList<>();
    private final List<ServiceComponentLink> serviceComponents = new ArrayList<>();
    private final Map<UUID, List<Owner>> owners = new HashMap<>();

    @BeforeEach
    void inMemoryStore() {
        when(store.findSoftwareComponent(any()))
                .thenAnswer(call -> Optional.ofNullable(components.get((UUID) call.getArgument(0))));
        when(store.findComponentIdsDependingOn(any())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return dependencies.stream().filter(edge -> ids.contains(edge[1])).map(edge -> edge[0])
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        });
        when(store.findServiceComponentLinks(any())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return serviceComponents.stream().filter(link -> ids.contains(link.componentId())).toList();
        });
        when(store.findBusinessServicesByIds(any())).thenAnswer(call -> byId(call.getArgument(0), services));
        when(store.findSoftwareComponentsByIds(any())).thenAnswer(call -> byId(call.getArgument(0), components));
        when(store.findOwnersForServices(any())).thenAnswer(call -> byId(call.getArgument(0), owners));
    }

    @Test
    void directUsageIsReportedAsDirectWithOwners() {
        SoftwareComponent lib = component("lib");
        BusinessService checkout = service("Checkout");
        uses(checkout, lib);
        Owner team = owner(checkout, "Checkout team");

        ComponentImpactResponse impact = inventory.getComponentImpact(lib.getId());

        assertThat(impact.component().id()).isEqualTo(lib.getId());
        assertThat(impact.services()).singleElement().satisfies(hit -> {
            assertThat(hit.service().id()).isEqualTo(checkout.getId());
            assertThat(hit.direct()).isTrue();
            assertThat(hit.viaComponent().id()).isEqualTo(lib.getId());
            assertThat(hit.owners()).extracting(InventoryDtos.OwnerResponse::id).containsExactly(team.getId());
        });
    }

    @Test
    void deepChainsAreTraversedWithOneBatchedQueryPerLevel() {
        SoftwareComponent previous = component("level-0");
        SoftwareComponent vulnerable = previous;
        for (int level = 1; level <= 5; level++) {
            SoftwareComponent next = component("level-" + level);
            dependsOn(next, previous);
            previous = next;
        }
        BusinessService service = service("Deep");
        uses(service, previous);

        ComponentImpactResponse impact = inventory.getComponentImpact(vulnerable.getId());

        assertThat(impact.services()).singleElement().satisfies(hit -> {
            assertThat(hit.direct()).isFalse();
            assertThat(hit.viaComponent().name()).isEqualTo("level-5");
        });
        // Five levels of dependents plus the final empty frontier — never one query per node.
        verify(store, times(6)).findComponentIdsDependingOn(any());
    }

    @Test
    void diamondsReportEachServiceOnce() {
        SoftwareComponent base = component("base");
        SoftwareComponent left = component("left");
        SoftwareComponent right = component("right");
        SoftwareComponent top = component("top");
        dependsOn(left, base);
        dependsOn(right, base);
        dependsOn(top, left);
        dependsOn(top, right);
        BusinessService service = service("Diamond");
        uses(service, left);
        uses(service, top);

        ComponentImpactResponse impact = inventory.getComponentImpact(base.getId());

        assertThat(impact.services()).singleElement()
                .satisfies(hit -> assertThat(hit.direct()).isFalse());
    }

    @Test
    void cyclesTerminateAndStillReachEveryDependentService() {
        SoftwareComponent target = component("target");
        SoftwareComponent a = component("a");
        SoftwareComponent b = component("b");
        dependsOn(a, target);
        dependsOn(b, a);
        dependsOn(a, b);
        dependsOn(target, b);
        BusinessService service = service("Cyclic");
        uses(service, b);

        ComponentImpactResponse impact = inventory.getComponentImpact(target.getId());

        assertThat(impact.services()).singleElement()
                .satisfies(hit -> assertThat(hit.viaComponent().id()).isEqualTo(b.getId()));
    }

    @Test
    void directLinkWinsWhenAServiceAlsoReachesTheComponentTransitively() {
        SoftwareComponent lib = component("lib");
        SoftwareComponent framework = component("framework");
        dependsOn(framework, lib);
        BusinessService service = service("Both");
        uses(service, framework);
        uses(service, lib);

        ComponentImpactedServiceResponse hit = inventory.getComponentImpact(lib.getId()).services().getFirst();

        assertThat(hit.direct()).isTrue();
        assertThat(hit.viaComponent().id()).isEqualTo(lib.getId());
    }

    @Test
    void servicesAreSortedByNameIgnoringCaseAndMissingOwnersAreEmpty() {
        SoftwareComponent lib = component("lib");
        for (String name : List.of("charlie", "Alpha", "bravo")) {
            uses(service(name), lib);
        }

        List<ComponentImpactedServiceResponse> hits = inventory.getComponentImpact(lib.getId()).services();

        assertThat(hits).extracting(hit -> hit.service().name()).containsExactly("Alpha", "bravo", "charlie");
        assertThat(hits).allSatisfy(hit -> assertThat(hit.owners()).isEmpty());
    }

    @Test
    void unusedComponentHasNoImpact() {
        SoftwareComponent lonely = component("lonely");

        assertThat(inventory.getComponentImpact(lonely.getId()).services()).isEmpty();
        verify(store, never()).findBusinessServicesByIds(any());
    }

    @Test
    void unknownComponentIsNotFoundWithoutTraversing() {
        assertThatThrownBy(() -> inventory.getComponentImpact(UUID.randomUUID()))
                .isInstanceOf(ApiExceptions.NotFound.class);
        verify(store, never()).findComponentIdsDependingOn(any());
    }

    @Test
    void vendorImpactListsDirectServicesSortedWithOwners() {
        ThirdPartyVendor cloud = new ThirdPartyVendor(UUID.randomUUID(), "Cloud");
        BusinessService zulu = service("Zulu");
        BusinessService alpha = service("alpha");
        Owner ops = owner(zulu, "Ops");
        when(store.findThirdPartyVendor(cloud.getId())).thenReturn(Optional.of(cloud));
        when(store.findServiceIdsForVendor(cloud.getId())).thenReturn(List.of(zulu.getId(), alpha.getId()));

        VendorImpactResponse impact = inventory.getVendorImpact(cloud.getId());

        assertThat(impact.vendor().id()).isEqualTo(cloud.getId());
        assertThat(impact.services()).extracting(hit -> hit.service().name()).containsExactly("alpha", "Zulu");
        assertThat(impact.services().get(1).owners()).extracting(InventoryDtos.OwnerResponse::id)
                .containsExactly(ops.getId());
    }

    @Test
    void unknownVendorIsNotFound() {
        when(store.findThirdPartyVendor(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventory.getVendorImpact(UUID.randomUUID()))
                .isInstanceOf(ApiExceptions.NotFound.class);
    }

    private SoftwareComponent component(String name) {
        SoftwareComponent component = new SoftwareComponent(UUID.randomUUID(), "npm", name, "1.0.0");
        components.put(component.getId(), component);
        return component;
    }

    private BusinessService service(String name) {
        BusinessService service = new BusinessService(UUID.randomUUID(), name, BusinessCriticality.MEDIUM);
        services.put(service.getId(), service);
        return service;
    }

    private Owner owner(BusinessService service, String name) {
        Owner owner = new Owner(UUID.randomUUID(), UUID.randomUUID(), name, null);
        owners.computeIfAbsent(service.getId(), key -> new ArrayList<>()).add(owner);
        return owner;
    }

    private void dependsOn(SoftwareComponent dependent, SoftwareComponent dependency) {
        dependencies.add(new UUID[]{dependent.getId(), dependency.getId()});
    }

    private void uses(BusinessService service, SoftwareComponent component) {
        serviceComponents.add(new ServiceComponentLink(service.getId(), component.getId()));
    }

    private static <T> Map<UUID, T> byId(Collection<UUID> ids, Map<UUID, T> source) {
        Map<UUID, T> result = new LinkedHashMap<>();
        ids.stream().filter(source::containsKey).forEach(id -> result.put(id, source.get(id)));
        return result;
    }
}
