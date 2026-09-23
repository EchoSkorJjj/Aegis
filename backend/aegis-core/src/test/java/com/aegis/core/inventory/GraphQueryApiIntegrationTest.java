package com.aegis.core.inventory;

import java.util.UUID;

import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import com.aegis.core.support.TestOwners;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GraphQueryApiIntegrationTest extends AbstractInventoryIntegrationTest {

    @Autowired
    private InventoryService inventory;

    @Autowired
    private TestOwners owners;

    /**
     * checkout (direct) --uses--> web (direct dep of vulnerable-lib)
     * checkout            --uses--> api  --depends on--> web --depends on--> vulnerable-lib
     * other-service       --uses--> unrelated-lib (no path to vulnerable-lib)
     */
    @Test
    void componentImpactIncludesDirectAndTransitiveServicesWithOwners() throws Exception {
        String suffix = UUID.randomUUID().toString();
        BusinessService checkout = inventory.createBusinessService("Checkout " + suffix, BusinessCriticality.HIGH);
        BusinessService other = inventory.createBusinessService("Other " + suffix, BusinessCriticality.LOW);
        Owner checkoutOwner = owners.assign(checkout.getId(), "Checkout team " + suffix);
        Owner otherOwner = owners.assign(other.getId(), "Other team " + suffix);

        SoftwareComponent vulnerable = inventory.createSoftwareComponent("npm", "vulnerable-lib-" + suffix, "1.0");
        SoftwareComponent web = inventory.createSoftwareComponent("npm", "web-" + suffix, "1.0");
        SoftwareComponent api = inventory.createSoftwareComponent("npm", "api-" + suffix, "1.0");
        SoftwareComponent unrelated = inventory.createSoftwareComponent("npm", "unrelated-lib-" + suffix, "1.0");

        // web depends directly on the vulnerable component.
        inventory.addComponentDependency(web.getId(), vulnerable.getId());
        // api depends on web, two hops away from the vulnerable component.
        inventory.addComponentDependency(api.getId(), web.getId());

        // checkout uses the vulnerable component directly AND transitively via api.
        inventory.addServiceComponent(checkout.getId(), vulnerable.getId());
        inventory.addServiceComponent(checkout.getId(), api.getId());
        // other uses an unrelated component only.
        inventory.addServiceComponent(other.getId(), unrelated.getId());

        mvc.perform(get("/api/inventory/components/{id}/impact", vulnerable.getId()).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.component.id").value(vulnerable.getId().toString()))
                .andExpect(jsonPath("$.services.length()").value(1))
                .andExpect(jsonPath("$.services[0].service.id").value(checkout.getId().toString()))
                .andExpect(jsonPath("$.services[0].direct").value(true))
                .andExpect(jsonPath("$.services[0].owners.length()").value(1))
                .andExpect(jsonPath("$.services[0].owners[0].id").value(checkoutOwner.getId().toString()));

        // Only web (direct) and api (2 hops) sit between checkout's usage and the vulnerable
        // component; unrelated is not reachable, so "web-only" impact excludes the other service.
        mvc.perform(get("/api/inventory/components/{id}/impact", web.getId()).with(role("SERVICE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services.length()").value(1))
                .andExpect(jsonPath("$.services[0].service.id").value(checkout.getId().toString()))
                .andExpect(jsonPath("$.services[0].direct").value(false))
                .andExpect(jsonPath("$.services[0].viaComponent.id").value(api.getId().toString()));

        mvc.perform(get("/api/inventory/components/{id}/impact", unrelated.getId()).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services.length()").value(1))
                .andExpect(jsonPath("$.services[0].service.id").value(other.getId().toString()));
    }

    @Test
    void componentImpactReportsDiamondsOnceAndTerminatesOnCycles() throws Exception {
        String suffix = UUID.randomUUID().toString();
        BusinessService service = inventory.createBusinessService("Diamond " + suffix, BusinessCriticality.MEDIUM);
        SoftwareComponent base = inventory.createSoftwareComponent("npm", "base-" + suffix, "1.0");
        SoftwareComponent left = inventory.createSoftwareComponent("npm", "left-" + suffix, "1.0");
        SoftwareComponent right = inventory.createSoftwareComponent("npm", "right-" + suffix, "1.0");
        SoftwareComponent top = inventory.createSoftwareComponent("npm", "top-" + suffix, "1.0");
        SoftwareComponent loopA = inventory.createSoftwareComponent("npm", "loop-a-" + suffix, "1.0");
        SoftwareComponent loopB = inventory.createSoftwareComponent("npm", "loop-b-" + suffix, "1.0");

        // Diamond: top -> left -> base and top -> right -> base.
        inventory.addComponentDependency(left.getId(), base.getId());
        inventory.addComponentDependency(right.getId(), base.getId());
        inventory.addComponentDependency(top.getId(), left.getId());
        inventory.addComponentDependency(top.getId(), right.getId());
        // A cycle above the diamond: loopA <-> loopB, with loopA depending on top.
        inventory.addComponentDependency(loopA.getId(), top.getId());
        inventory.addComponentDependency(loopB.getId(), loopA.getId());
        inventory.addComponentDependency(loopA.getId(), loopB.getId());
        inventory.addServiceComponent(service.getId(), loopB.getId());

        mvc.perform(get("/api/inventory/components/{id}/impact", base.getId()).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services.length()").value(1))
                .andExpect(jsonPath("$.services[0].service.id").value(service.getId().toString()))
                .andExpect(jsonPath("$.services[0].direct").value(false))
                .andExpect(jsonPath("$.services[0].viaComponent.id").value(loopB.getId().toString()))
                .andExpect(jsonPath("$.services[0].owners.length()").value(0));
    }

    @Test
    void vendorImpactListsServicesUsingTheVendorDirectlyWithOwners() throws Exception {
        String suffix = UUID.randomUUID().toString();
        BusinessService checkout = inventory.createBusinessService("Checkout " + suffix, BusinessCriticality.HIGH);
        BusinessService other = inventory.createBusinessService("Other " + suffix, BusinessCriticality.LOW);
        Owner owner = owners.assign(checkout.getId(), "Checkout team " + suffix);

        ThirdPartyVendor vendor = inventory.createThirdPartyVendor("Cloud vendor " + suffix);
        inventory.addServiceVendor(checkout.getId(), vendor.getId());

        mvc.perform(get("/api/inventory/vendors/{id}/impact", vendor.getId()).with(role("COMPLIANCE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vendor.id").value(vendor.getId().toString()))
                .andExpect(jsonPath("$.services.length()").value(1))
                .andExpect(jsonPath("$.services[0].service.id").value(checkout.getId().toString()))
                .andExpect(jsonPath("$.services[0].owners[0].id").value(owner.getId().toString()));

        mvc.perform(get("/api/inventory/vendors/{id}/impact", other.getId()).with(role("ANALYST")))
                .andExpect(status().isNotFound());
    }

    @Test
    void impactEndpointsRequireInventoryReadRole() throws Exception {
        UUID componentId = inventory.createSoftwareComponent(
                "npm", "auth-check-" + UUID.randomUUID(), "1.0").getId();

        mvc.perform(get("/api/inventory/components/{id}/impact", componentId))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/inventory/components/{id}/impact", componentId).with(role("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/inventory/components/{id}/impact", componentId).with(role("ANALYST")))
                .andExpect(status().isMethodNotAllowed());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor role(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
