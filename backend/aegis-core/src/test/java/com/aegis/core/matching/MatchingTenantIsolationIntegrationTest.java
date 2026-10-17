package com.aegis.core.matching;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.platform.tenant.CurrentOrg;
import com.aegis.core.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MatchingTenantIsolationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CurrentOrg currentOrg;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private AdvisoryStore advisories;

    @Test
    void theSameGlobalAdvisoryIsContextualizedOnlyAgainstTheActiveOrgsInventory() throws Exception {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID[] serviceA = new UUID[1];
        String packageName = "tenant-lib-" + UUID.randomUUID();

        currentOrg.runAs(orgA, () -> {
            BusinessService service = inventory.createBusinessService(
                    "Org A service " + packageName, BusinessCriticality.HIGH);
            SoftwareComponent component = inventory.createSoftwareComponent("npm", packageName, "1.5.0");
            inventory.addServiceComponent(service.getId(), component.getId());
            serviceA[0] = service.getId();
        });
        currentOrg.runAs(orgB, () ->
                inventory.createSoftwareComponent("npm", "unrelated-" + UUID.randomUUID(), "1.5.0"));

        Advisory advisory = advisories.upsert(new Advisory(
                null,
                "nvd",
                "CVE-2099-" + Math.abs(UUID.randomUUID().getMostSignificantBits()),
                "tenant isolation test",
                Instant.now(),
                Instant.now(),
                null,
                List.of(new AffectedProduct(
                        "cpe:2.3:a:acme:" + packageName + ":*:*:*:*:*:*:*:*",
                        "a", "acme", packageName, null, "1.0.0", null, null, "2.0.0"))));

        mvc.perform(get("/api/matching/change-signals/{id}", advisory.id()).with(role(orgA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.impactedServices.length()").value(1))
                .andExpect(jsonPath("$.impactedServices[0].service.id").value(serviceA[0].toString()));

        mvc.perform(get("/api/matching/change-signals/{id}", advisory.id()).with(role(orgB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedComponentCount").value(0))
                .andExpect(jsonPath("$.impactedServices.length()").value(0));
    }

    private static RequestPostProcessor role(UUID org) {
        return jwt()
                .jwt(builder -> builder.claim("org", org.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST"));
    }
}
