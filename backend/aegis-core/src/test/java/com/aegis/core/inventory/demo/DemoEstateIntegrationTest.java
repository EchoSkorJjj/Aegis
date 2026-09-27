package com.aegis.core.inventory.demo;

import java.util.UUID;

import com.aegis.core.inventory.InventoryFilters;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The demo estate seeded through the real SBOM import, queried through the real graph API. */
class DemoEstateIntegrationTest extends AbstractInventoryIntegrationTest {

    private static final PageRequest FIRST = PageRequest.of(0, 1);
    private static final PageRequest ALL = PageRequest.of(0, 100);

    @Autowired
    private DemoEstateSeeder seeder;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private InventoryStore store;

    @BeforeEach
    void seedDemoEstate() {
        seeder.seed();
    }

    @Test
    void seedingAgainChangesNothing() {
        long services = inventory.listBusinessServices(InventoryFilters.ServiceFilter.NONE, FIRST).getTotalElements();
        long components = inventory.listSoftwareComponents(InventoryFilters.ComponentFilter.NONE, FIRST).getTotalElements();
        long vendors = inventory.listThirdPartyVendors(InventoryFilters.NamedFilter.NONE, FIRST).getTotalElements();
        long owners = inventory.listOwners(InventoryFilters.NamedFilter.NONE, FIRST).getTotalElements();

        seeder.seed();

        assertThat(inventory.listBusinessServices(InventoryFilters.ServiceFilter.NONE, FIRST).getTotalElements()).isEqualTo(services);
        assertThat(inventory.listSoftwareComponents(InventoryFilters.ComponentFilter.NONE, FIRST).getTotalElements()).isEqualTo(components);
        assertThat(inventory.listThirdPartyVendors(InventoryFilters.NamedFilter.NONE, FIRST).getTotalElements()).isEqualTo(vendors);
        assertThat(inventory.listOwners(InventoryFilters.NamedFilter.NONE, FIRST).getTotalElements()).isEqualTo(owners);

        UUID payments = serviceId("Payments API");
        assertThat(inventory.listServiceComponents(payments, ALL).getContent())
                .extracting(SoftwareComponent::getName)
                .containsExactlyInAnyOrder(
                        "org.springframework.boot:spring-boot-starter-web",
                        "org.springframework.boot:spring-boot-starter-log4j2",
                        "org.postgresql:postgresql");
        // The seed assigns no owners: an owner is a verified organization member, and the seed runs with
        // no user to verify one against (DemoEstateSeeder). They are assigned from each service's page.
        assertThat(inventory.listServiceOwners(payments, ALL).getContent()).isEmpty();
        assertThat(store.findSoftwareComponentByCoordinates("maven", "com.demo:payments-api", "1.4.0"))
                .as("the SBOM root is the service itself, not a component").isEmpty();
    }

    @Test
    void log4jCoreReachesOrderServiceDirectlyAndPaymentsThroughThreeHops() throws Exception {
        String uses = version("2.14.1");
        usedBy("maven", "org.apache.logging.log4j:log4j-core")
                .andExpect(jsonPath(uses + ".service.name", contains("Order Service", "Payments API")))
                .andExpect(jsonPath(uses + ".direct", contains(true, false)))
                .andExpect(jsonPath(uses + ".service.criticality", contains("HIGH", "CRITICAL")))
                .andExpect(jsonPath(uses + ".through[0].name",
                        contains("org.springframework.boot:spring-boot-starter-log4j2")))
                // The demo seeds no owners (owners are verified organization members).
                .andExpect(jsonPath(uses + ".owners[*]").isEmpty());
    }

    @Test
    void jacksonDatabindIsDirectForOrdersAndTransitiveForPayments() throws Exception {
        String uses = version("2.13.0");
        usedBy("maven", "com.fasterxml.jackson.core:jackson-databind")
                .andExpect(jsonPath(uses + ".service.name", contains("Order Service", "Payments API")))
                .andExpect(jsonPath(uses + ".direct", contains(true, false)))
                .andExpect(jsonPath(uses + ".through[0].name",
                        contains("org.springframework.boot:spring-boot-starter-web")));
    }

    @Test
    void directUsageWinsWhenLodashIsAlsoPulledInByTheUiKit() throws Exception {
        String uses = version("4.17.20");
        usedBy("npm", "lodash")
                .andExpect(jsonPath(uses + ".service.name", contains("Customer Portal")))
                .andExpect(jsonPath(uses + ".direct", contains(true)));
    }

    @Test
    void reactReachedThroughTwoPathsIsReportedOnce() throws Exception {
        String uses = version("17.0.2");
        usedBy("npm", "react")
                .andExpect(jsonPath(uses + ".service.name", contains("Customer Portal")))
                .andExpect(jsonPath(uses + ".direct", contains(false)));
    }

    @Test
    void transitivePythonDependencyReachesInternalReporting() throws Exception {
        String uses = version("1.26.4");
        usedBy("pypi", "urllib3")
                .andExpect(jsonPath(uses + ".service.name", contains("Internal Reporting")))
                .andExpect(jsonPath(uses + ".through[0].name", contains("requests")));
    }

    @Test
    void vendorUsedByCoversServicesWithAndWithoutSboms() throws Exception {
        vendorUsedBy("Amazon Web Services")
                .andExpect(jsonPath("$.services[*].service.name", contains("Order Service", "Payments API")));
        vendorUsedBy("Cloudflare")
                .andExpect(jsonPath("$.services[*].service.name", contains("Customer Portal", "Marketing Site")))
                .andExpect(jsonPath("$.services[1].owners").isEmpty());
    }

    /** The uses of one version, from a package's "Used by". */
    private static String version(String version) {
        return "$.uses[?(@.component.version == '" + version + "')]";
    }

    private ResultActions usedBy(String ecosystem, String name) throws Exception {
        return mvc.perform(get("/api/inventory/components/used-by").param("ecosystem", ecosystem).param("name", name)
                        .with(role("SERVICE_OWNER")))
                .andExpect(status().isOk());
    }

    private ResultActions vendorUsedBy(String vendorName) throws Exception {
        UUID id = store.findThirdPartyVendorByName(vendorName).orElseThrow().getId();
        return mvc.perform(get("/api/inventory/vendors/{id}/used-by", id).with(role("ANALYST")))
                .andExpect(status().isOk());
    }

    private UUID serviceId(String name) {
        return store.findBusinessServiceByName(name).orElseThrow().getId();
    }

    private static RequestPostProcessor role(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
