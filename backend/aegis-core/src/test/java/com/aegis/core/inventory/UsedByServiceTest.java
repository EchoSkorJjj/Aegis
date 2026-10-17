package com.aegis.core.inventory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.aegis.core.inventory.InventoryDtos.PackageUsageResponse;
import com.aegis.core.inventory.InventoryDtos.PackageUseResponse;
import com.aegis.core.inventory.InventoryDtos.SoftwareComponentResponse;
import com.aegis.core.inventory.InventoryDtos.VendorUsageResponse;
import com.aegis.core.inventory.InventoryStore.ComponentDependencyLink;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** "Used by" over an in-memory store (no Docker): every version of a package, direct or through what, and vendors. */
class UsedByServiceTest {

    private final InventoryStore store = mock(InventoryStore.class);
    private final UsedByService usedBy = new UsedByService(store, new DependencyGraph(store));

    private final Map<UUID, SoftwareComponent> components = new HashMap<>();
    private final Map<UUID, BusinessService> services = new HashMap<>();
    private final List<ComponentDependencyLink> edges = new ArrayList<>();
    private final List<ServiceComponentLink> links = new ArrayList<>();
    private final Map<UUID, List<Owner>> owners = new HashMap<>();

    @BeforeEach
    void inMemoryStore() {
        when(store.findSoftwareComponentsByPackage(any(), any())).thenAnswer(call -> components.values().stream()
                .filter(component -> component.getEcosystem().equalsIgnoreCase(call.getArgument(0))
                        && component.getName().equalsIgnoreCase(call.getArgument(1)))
                .toList());
        when(store.findComponentDependencyLinks(any())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return edges.stream().filter(edge -> ids.contains(edge.dependencyId())).toList();
        });
        when(store.findServiceComponentLinks(any())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return links.stream().filter(link -> ids.contains(link.componentId())).toList();
        });
        when(store.findBusinessServicesByIds(any())).thenAnswer(call -> byId(call.getArgument(0), services));
        when(store.findSoftwareComponentsByIds(any())).thenAnswer(call -> byId(call.getArgument(0), components));
        when(store.findOwnersForServices(any())).thenAnswer(call -> byId(call.getArgument(0), owners));
    }

    @Test
    void aServiceLinkingTheVersionUsesItDirectlyWithItsOwners() {
        SoftwareComponent lib = component("lib", "1.0.0");
        BusinessService checkout = service("Checkout");
        uses(checkout, lib);
        Owner team = owner(checkout, "Checkout team");

        PackageUsageResponse usage = usedBy.packageUsage("NPM", "LIB");

        assertThat(usage.ecosystem()).isEqualTo("npm");
        assertThat(usage.name()).isEqualTo("lib");
        assertThat(usage.uses()).singleElement().satisfies(use -> {
            assertThat(use.service().id()).isEqualTo(checkout.getId());
            assertThat(use.component().id()).isEqualTo(lib.getId());
            assertThat(use.direct()).isTrue();
            assertThat(use.through()).isEmpty();
            assertThat(use.owners()).extracting(InventoryDtos.OwnerResponse::id).containsExactly(team.getId());
        });
    }

    @Test
    void aTransitiveUseNamesTheChainFromTheComponentTheServiceLinks() {
        SoftwareComponent lib = component("lib", "1.0.0");
        SoftwareComponent parser = component("parser", "2.0.0");
        SoftwareComponent framework = component("framework", "3.0.0");
        BusinessService service = service("Deep");
        declares(service, parser, lib);
        declares(service, framework, parser);
        uses(service, framework);

        PackageUseResponse use = usedBy.packageUsage("npm", "lib").uses().getFirst();

        assertThat(use.direct()).isFalse();
        assertThat(use.through()).extracting(SoftwareComponentResponse::name).containsExactly("framework", "parser");
    }

    @Test
    void everyVersionOfThePackageIsCoveredOneRowPerServiceAndVersionInNaturalOrder() {
        SoftwareComponent old = component("axios", "1.5.0");
        SoftwareComponent patched = component("axios", "1.5.1");
        SoftwareComponent current = component("axios", "1.18.0");
        component("axios-retry", "3.0.0");
        BusinessService health = service("HealthcareApp");
        BusinessService billing = service("billing");
        uses(health, current);
        uses(health, patched);
        uses(health, old);
        SoftwareComponent sdk = component("payments-sdk", "4.0.0");
        declares(billing, sdk, old);
        uses(billing, sdk);

        List<PackageUseResponse> uses = usedBy.packageUsage("npm", "axios").uses();

        assertThat(uses).extracting(use -> use.service().name() + " " + use.component().version() + " " + use.direct())
                .containsExactly("billing 1.5.0 false", "HealthcareApp 1.5.0 true", "HealthcareApp 1.5.1 true",
                        "HealthcareApp 1.18.0 true");
    }

    @Test
    void aDirectLinkWinsWhenTheServiceAlsoReachesTheVersionThroughAnotherComponent() {
        SoftwareComponent lib = component("lib", "1.0.0");
        SoftwareComponent framework = component("framework", "1.0.0");
        BusinessService service = service("Both");
        declares(service, framework, lib);
        uses(service, framework);
        uses(service, lib);

        assertThat(usedBy.packageUsage("npm", "lib").uses()).singleElement()
                .satisfies(use -> assertThat(use.direct()).isTrue());
    }

    @Test
    void aDiamondReportsTheServiceOnceTheShortestWay() {
        SoftwareComponent base = component("base", "1.0.0");
        SoftwareComponent left = component("left", "1.0.0");
        SoftwareComponent right = component("right", "1.0.0");
        SoftwareComponent top = component("top", "1.0.0");
        BusinessService service = service("Diamond");
        declares(service, left, base);
        declares(service, right, base);
        declares(service, top, left);
        declares(service, top, right);
        uses(service, left);
        uses(service, top);

        assertThat(usedBy.packageUsage("npm", "base").uses()).singleElement()
                .satisfies(use -> assertThat(use.through()).extracting(SoftwareComponentResponse::name)
                        .containsExactly("left"));
    }

    @Test
    void anotherServicesResolutionIsNeverCredited() {
        SoftwareComponent http = component("http", "1.0.0");
        SoftwareComponent oldRedirects = component("redirects", "1.0.0");
        SoftwareComponent newRedirects = component("redirects", "2.0.0");
        BusinessService stale = service("Stale");
        BusinessService current = service("Current");
        uses(stale, http);
        uses(current, http);
        declares(stale, http, oldRedirects);
        declares(current, http, newRedirects);

        assertThat(usedBy.packageUsage("npm", "redirects").uses())
                .extracting(use -> use.service().name() + " " + use.component().version())
                .containsExactly("Current 2.0.0", "Stale 1.0.0");
    }

    @Test
    void servicesAreSortedByNameIgnoringCaseAndMissingOwnersAreEmpty() {
        SoftwareComponent lib = component("lib", "1.0.0");
        for (String name : List.of("charlie", "Alpha", "bravo")) {
            uses(service(name), lib);
        }

        List<PackageUseResponse> uses = usedBy.packageUsage("npm", "lib").uses();

        assertThat(uses).extracting(use -> use.service().name()).containsExactly("Alpha", "bravo", "charlie");
        assertThat(uses).allSatisfy(use -> assertThat(use.owners()).isEmpty());
    }

    @Test
    void aPackageNothingUsesHasNoUsesAndOneNotInTheInventoryIsNotWalked() {
        component("lonely", "1.0.0");

        assertThat(usedBy.packageUsage("npm", "lonely").uses()).isEmpty();
        verify(store, never()).findBusinessServicesByIds(any());

        PackageUsageResponse unknown = usedBy.packageUsage("npm", "left-pad");
        assertThat(unknown.uses()).isEmpty();
        assertThat(unknown.name()).isEqualTo("left-pad");
    }

    @Test
    void thePackageMustBeNamed() {
        assertThatThrownBy(() -> usedBy.packageUsage(" ", "lib")).isInstanceOf(ApiExceptions.BadRequest.class);
        assertThatThrownBy(() -> usedBy.packageUsage("npm", null)).isInstanceOf(ApiExceptions.BadRequest.class);
        verify(store, never()).findSoftwareComponentsByPackage(any(), any());
    }

    @Test
    void aVendorsServicesAreListedByNameWithTheirOwners() {
        ThirdPartyVendor cloud = new ThirdPartyVendor(UUID.randomUUID(), "Cloud");
        BusinessService zulu = service("Zulu");
        BusinessService alpha = service("alpha");
        Owner ops = owner(zulu, "Ops");
        when(store.findThirdPartyVendor(cloud.getId())).thenReturn(Optional.of(cloud));
        when(store.findServiceIdsForVendor(cloud.getId())).thenReturn(List.of(zulu.getId(), alpha.getId()));

        VendorUsageResponse usage = usedBy.vendorUsage(cloud.getId());

        assertThat(usage.vendor().id()).isEqualTo(cloud.getId());
        assertThat(usage.services()).extracting(using -> using.service().name()).containsExactly("alpha", "Zulu");
        assertThat(usage.services().get(1).owners()).extracting(InventoryDtos.OwnerResponse::id)
                .containsExactly(ops.getId());
    }

    @Test
    void anUnknownVendorIsNotFound() {
        when(store.findThirdPartyVendor(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> usedBy.vendorUsage(UUID.randomUUID())).isInstanceOf(ApiExceptions.NotFound.class);
    }

    @Test
    void versionsCompareNumberByNumber() {
        assertThat(UsedByService.compareVersions("1.5.0", "1.18.0")).isNegative();
        assertThat(UsedByService.compareVersions("2.0.0", "10.0.0")).isNegative();
        assertThat(UsedByService.compareVersions("1.0.0-RC1", "1.0.0-rc1")).isZero();
        assertThat(UsedByService.compareVersions("1.0", "1.0.1")).isNegative();
        assertThat(UsedByService.compareVersions("99999999999999999999", "1")).isPositive();
    }

    private SoftwareComponent component(String name, String version) {
        SoftwareComponent component = new SoftwareComponent(UUID.randomUUID(), "npm", name, version);
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

    private void uses(BusinessService service, SoftwareComponent component) {
        links.add(new ServiceComponentLink(service.getId(), component.getId()));
    }

    private void declares(BusinessService service, SoftwareComponent component, SoftwareComponent dependency) {
        edges.add(new ComponentDependencyLink(component.getId(), dependency.getId(), service.getId()));
    }

    private static <T> Map<UUID, T> byId(Collection<UUID> ids, Map<UUID, T> values) {
        Map<UUID, T> found = new HashMap<>();
        ids.forEach(id -> {
            if (values.containsKey(id)) {
                found.put(id, values.get(id));
            }
        });
        return found;
    }
}
