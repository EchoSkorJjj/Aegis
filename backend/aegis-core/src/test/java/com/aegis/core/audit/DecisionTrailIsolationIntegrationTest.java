package com.aegis.core.audit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.platform.tenant.CurrentOrg;
import com.aegis.core.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The decision audit trail is each organization's own (E10 story 1). Unlike the other trail tests, no organization
 * is set for the test: the estate is built in a fresh one, and each request's organization is its token's, so a
 * token for another organization is what has to see nothing.
 */
class DecisionTrailIsolationIntegrationTest extends AbstractIntegrationTest {

    private static final String TRAIL = "/api/audit/decisions";

    @Autowired private CurrentOrg currentOrg;
    @Autowired private InventoryService inventory;
    @Autowired private AdvisoryStore advisories;

    @Test
    void anotherOrganizationsTokenSeesNoneOfTheTrail() throws Exception {
        UUID org = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        String tag = Long.toHexString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        String artifact = "com.acme:isolated-lib-" + tag;
        AtomicReference<BusinessService> service = new AtomicReference<>();
        currentOrg.runAs(org, () -> {
            service.set(inventory.createBusinessService("Isolated " + tag, BusinessCriticality.HIGH,
                    ServiceExposure.INTERNET_FACING));
            SoftwareComponent component = inventory.createSoftwareComponent("maven", artifact, "1.0.0");
            inventory.addServiceComponent(service.get().getId(), component.getId());
        });
        Advisory advisory = advisories.upsert(new Advisory(null, "osv", "GHSA-" + tag, "CVE-2094-" + tag,
                "A flaw in isolated-lib.", Instant.now(), Instant.now(),
                new Advisory.Cvss("3.1", null, new BigDecimal("9.8"), "CRITICAL"),
                List.of(AffectedProduct.ofPackage("Maven", artifact, null, "1.0.0", null, null, "1.1.0"))));

        String created = mvc.perform(post("/api/actions/risk-tickets").with(as("ANALYST", org))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeSignalId\":\"%s\",\"serviceId\":\"%s\"}"
                                .formatted(advisory.id(), service.get().getId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String response = JsonPath.read(created, "$.id");

        String trail = mvc.perform(get(TRAIL).param("responseId", response).with(as("COMPLIANCE_ADMIN", org)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn().getResponse().getContentAsString();
        String record = JsonPath.read(trail, "$.content[0].id");
        mvc.perform(get(TRAIL + "/{id}", record).with(as("COMPLIANCE_ADMIN", org)))
                .andExpect(status().isOk());

        // Governance elsewhere, asking for the same response, service and record, finds nothing.
        mvc.perform(get(TRAIL).param("responseId", response).with(as("COMPLIANCE_ADMIN", other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(TRAIL).param("serviceId", service.get().getId().toString())
                        .with(as("ANALYST", other)))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(TRAIL + "/{id}", record).with(as("COMPLIANCE_ADMIN", other)))
                .andExpect(status().isNotFound());
    }

    private static RequestPostProcessor as(String role, UUID org) {
        return jwt().jwt(token -> token.subject(UUID.randomUUID().toString()).claim("org", org.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
