package com.aegis.core.findings;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.incidents.VendorIncident;
import com.aegis.core.incidents.VendorIncidentStore;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import com.aegis.core.platform.tenant.CurrentOrg;
import com.aegis.core.support.AbstractIntegrationTest;
import com.aegis.core.support.TestOwners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fresh orgs and packages make ranking, paging and isolation independent of shared-suite row order. */
class FindingApiIntegrationTest extends AbstractIntegrationTest {
    @Autowired private CurrentOrg currentOrg;
    @Autowired private InventoryService inventory;
    @Autowired private AdvisoryStore advisories;
    @Autowired private VendorIncidentStore incidents;
    @Autowired private TestOwners owners;

    private UUID org;
    private BusinessService publicService;
    private BusinessService internalService;
    private Owner owner;
    private Advisory critical;
    private VendorIncident outage;

    @BeforeEach
    void estate() {
        org = UUID.randomUUID();
        String tag = UUID.randomUUID().toString();
        String library = "finding-lib-" + tag;
        currentOrg.runAs(org, () -> {
            publicService = inventory.createBusinessService("Public " + tag,
                    BusinessCriticality.CRITICAL, ServiceExposure.INTERNET_FACING);
            internalService = inventory.createBusinessService("Internal " + tag,
                    BusinessCriticality.HIGH, ServiceExposure.ISOLATED);
            owner = owners.assign(internalService.getId(), "Owner " + tag);
            SoftwareComponent direct = inventory.createSoftwareComponent("npm", "direct-" + tag, "1.0.0");
            SoftwareComponent transitive = inventory.createSoftwareComponent("npm", library, "1.0.0");
            inventory.addComponentDependency(direct.getId(), transitive.getId());
            inventory.addServiceComponent(publicService.getId(), direct.getId());
            inventory.addServiceComponent(internalService.getId(), transitive.getId());
            ThirdPartyVendor vendor = inventory.createThirdPartyVendor("Cloud " + tag);
            inventory.addServiceVendor(internalService.getId(), vendor.getId());
        });
        critical = advisory(tag + "1", library, "10.0");
        advisory(tag + "2", library, "6.0");
        advisory(tag + "3", library, "0.1"); // Below materiality for every service.
        outage = incidents.upsert(new VendorIncident(null, "vendor-status", "outage-" + tag,
                "cloud-" + tag, "Cloud " + tag, "API outage", "investigating", "major", null,
                "Elevated errors", Instant.now(), null, null));
    }

    @Test
    void ranksMaterialChangesAcrossTypesBeforePagingAndKeepsOldTransitiveVulnerabilities() throws Exception {
        mvc.perform(get("/api/findings").param("size", "1").with(as("ANALYST", UUID.randomUUID(), org)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.totalElements").value(3))
                .andExpect(jsonPath("$.items.content[0].change.id").value(critical.id().toString()))
                .andExpect(jsonPath("$.items.content[0].score").value(100.0))
                .andExpect(jsonPath("$.items.content[0].services.length()").value(2));
        mvc.perform(get("/api/findings").param("size", "1").param("page", "2")
                        .with(as("ANALYST", UUID.randomUUID(), org)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.content[0].score").value(60.0));
    }

    @Test
    void filteringRanksOnlyTheSelectedServicesAndMatchesOwnerAndServiceOnTheSameService() throws Exception {
        mvc.perform(get("/api/findings").param("ownerId", owner.getId().toString())
                        .with(as("COMPLIANCE_ADMIN", UUID.randomUUID(), org)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.totalElements").value(3))
                .andExpect(jsonPath("$.items.content[0].change.id").value(outage.id().toString()))
                .andExpect(jsonPath("$.items.content[0].score").value(60.0))
                .andExpect(jsonPath("$.items.content[0].services[0].owners[0].id").value(owner.getId().toString()))
                .andExpect(jsonPath("$.items.content[1].score").value(40.0));
        mvc.perform(get("/api/findings").param("serviceId", publicService.getId().toString())
                        .param("ownerId", owner.getId().toString()).with(as("ANALYST", UUID.randomUUID(), org)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.totalElements").value(0));
    }

    @Test
    void mineUsesTheAuthenticatedUserAndFacetsDoNotCrossOrganizations() throws Exception {
        mvc.perform(get("/api/findings").param("mine", "true").with(as("SERVICE_OWNER", owner.getUserId(), org)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownedServiceCount").value(1))
                .andExpect(jsonPath("$.items.content[0].change.id").value(outage.id().toString()));
        mvc.perform(get("/api/findings").param("mine", "true")
                        .with(as("ANALYST", UUID.randomUUID(), org)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownedServiceCount").value(0))
                .andExpect(jsonPath("$.items.totalElements").value(0));
        mvc.perform(get("/api/findings").param("serviceId", publicService.getId().toString())
                        .with(as("ANALYST", UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.totalElements").value(0))
                .andExpect(jsonPath("$.services.length()").value(0))
                .andExpect(jsonPath("$.owners.length()").value(0));
    }

    @Test
    void enforcesDomainReadRolesAndRejectsInvalidFilters() throws Exception {
        mvc.perform(get("/api/findings")).andExpect(status().isUnauthorized());
        for (String role : List.of("USER", "ORG_ADMIN", "PLATFORM_ADMIN")) {
            mvc.perform(get("/api/findings").with(as(role, UUID.randomUUID(), org)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/findings").param("ownerId", "invalid")
                .with(as("ANALYST", UUID.randomUUID(), org))).andExpect(status().isBadRequest());
    }

    private Advisory advisory(String tag, String library, String score) {
        Instant old = Instant.parse("2021-01-01T00:00:00Z");
        return advisories.upsert(new Advisory(null, "nvd", "CVE-2099-" + tag, "Finding " + tag, old, old,
                new Advisory.Cvss("3.1", null, new BigDecimal(score), "CRITICAL"),
                List.of(AffectedProduct.ofPackage("npm", library, null, "0", null, null, "2.0.0"))));
    }

    private static RequestPostProcessor as(String role, UUID user, UUID org) {
        return jwt().jwt(builder -> builder.subject(user.toString()).claim("org", org.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
