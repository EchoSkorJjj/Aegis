package com.aegis.core.inventory.demo;

import java.util.UUID;

import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The demo estate seeded through the real SBOM import, queried through the real graph API. */
class DemoEstateIntegrationTest extends AbstractIntegrationTest {

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
        long services = inventory.listBusinessServices(FIRST).getTotalElements();
        long components = inventory.listSoftwareComponents(FIRST).getTotalElements();
        long vendors = inventory.listThirdPartyVendors(FIRST).getTotalElements();
        long owners = inventory.listOwners(FIRST).getTotalElements();

        seeder.seed();

        assertThat(inventory.listBusinessServices(FIRST).getTotalElements()).isEqualTo(services);
        assertThat(inventory.listSoftwareComponents(FIRST).getTotalElements()).isEqualTo(components);
        assertThat(inventory.listThirdPartyVendors(FIRST).getTotalElements()).isEqualTo(vendors);
        assertThat(inventory.listOwners(FIRST).getTotalElements()).isEqualTo(owners);

        UUID payments = serviceId("Payments API");
        assertThat(inventory.listServiceComponents(payments, ALL).getContent())
                .extracting(SoftwareComponent::getName)
                .containsExactlyInAnyOrder(
                        "org.springframework.boot:spring-boot-starter-web",
                        "org.springframework.boot:spring-boot-starter-log4j2",
                        "org.postgresql:postgresql");
        assertThat(inventory.listServiceOwners(payments, ALL).getContent())
                .extracting(Owner::getName).containsExactlyInAnyOrder("Payments Team", "Platform SRE");
        assertThat(store.findSoftwareComponentByCoordinates("maven", "com.demo:payments-api", "1.4.0"))
                .as("the SBOM root is the service itself, not a component").isEmpty();
    }

    @Test
    void log4jCoreReachesOrderServiceDirectlyAndPaymentsThroughThreeHops() throws Exception {
        componentImpact("maven", "org.apache.logging.log4j:log4j-core", "2.14.1")
                .andExpect(jsonPath("$.services[*].service.name", contains("Order Service", "Payments API")))
                .andExpect(jsonPath("$.services[0].direct").value(true))
                .andExpect(jsonPath("$.services[0].owners[*].name", contains("Commerce Team")))
                .andExpect(jsonPath("$.services[1].direct").value(false))
                .andExpect(jsonPath("$.services[1].service.criticality").value("CRITICAL"))
                .andExpect(jsonPath("$.services[1].viaComponent.name")
                        .value("org.springframework.boot:spring-boot-starter-log4j2"))
                .andExpect(jsonPath("$.services[1].owners[*].name",
                        containsInAnyOrder("Payments Team", "Platform SRE")));
    }

    @Test
    void jacksonDatabindIsDirectForOrdersAndTransitiveForPayments() throws Exception {
        componentImpact("maven", "com.fasterxml.jackson.core:jackson-databind", "2.13.0")
                .andExpect(jsonPath("$.services[*].service.name", contains("Order Service", "Payments API")))
                .andExpect(jsonPath("$.services[*].direct", contains(true, false)))
                .andExpect(jsonPath("$.services[1].viaComponent.name")
                        .value("org.springframework.boot:spring-boot-starter-web"));
    }

    @Test
    void directUsageWinsWhenLodashIsAlsoPulledInByTheUiKit() throws Exception {
        componentImpact("npm", "lodash", "4.17.20")
                .andExpect(jsonPath("$.services[*].service.name", contains("Customer Portal")))
                .andExpect(jsonPath("$.services[0].direct").value(true));
    }

    @Test
    void reactReachedThroughTwoPathsIsReportedOnce() throws Exception {
        componentImpact("npm", "react", "17.0.2")
                .andExpect(jsonPath("$.services[*].service.name", contains("Customer Portal")))
                .andExpect(jsonPath("$.services[0].direct").value(false));
    }

    @Test
    void transitivePythonDependencyReachesInternalReporting() throws Exception {
        componentImpact("pypi", "urllib3", "1.26.4")
                .andExpect(jsonPath("$.services[*].service.name", contains("Internal Reporting")))
                .andExpect(jsonPath("$.services[0].viaComponent.name").value("requests"))
                .andExpect(jsonPath("$.services[0].owners[*].name", contains("Data Team")));
    }

    @Test
    void vendorImpactCoversServicesWithAndWithoutSboms() throws Exception {
        vendorImpact("Amazon Web Services")
                .andExpect(jsonPath("$.services[*].service.name", contains("Order Service", "Payments API")));
        vendorImpact("Cloudflare")
                .andExpect(jsonPath("$.services[*].service.name", contains("Customer Portal", "Marketing Site")))
                .andExpect(jsonPath("$.services[1].owners[*].name", contains("Web Platform Team")));
    }

    private ResultActions componentImpact(String ecosystem, String name, String version) throws Exception {
        UUID id = store.findSoftwareComponentByCoordinates(ecosystem, name, version).orElseThrow().getId();
        return mvc.perform(get("/api/inventory/components/{id}/impact", id).with(role("SERVICE_OWNER")))
                .andExpect(status().isOk());
    }

    private ResultActions vendorImpact(String vendorName) throws Exception {
        UUID id = store.findThirdPartyVendorByName(vendorName).orElseThrow().getId();
        return mvc.perform(get("/api/inventory/vendors/{id}/impact", id).with(role("ANALYST")))
                .andExpect(status().isOk());
    }

    private UUID serviceId(String name) {
        return store.findBusinessServiceByName(name).orElseThrow().getId();
    }

    private static RequestPostProcessor role(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
