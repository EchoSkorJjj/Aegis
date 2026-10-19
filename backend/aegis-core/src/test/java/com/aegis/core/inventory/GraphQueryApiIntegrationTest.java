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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.contains;
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
     * checkout (direct) --uses--> vulnerable-lib
     * checkout            --uses--> api  --depends on--> web --depends on--> vulnerable-lib
     * other-service       --uses--> unrelated-lib (no path to vulnerable-lib)
     */
    @Test
    void usedByCoversDirectAndTransitiveUsesWithOwners() throws Exception {
        String suffix = UUID.randomUUID().toString();
        BusinessService checkout = inventory.createBusinessService("Checkout " + suffix, BusinessCriticality.HIGH);
        BusinessService other = inventory.createBusinessService("Other " + suffix, BusinessCriticality.LOW);
        Owner checkoutOwner = owners.assign(checkout.getId(), "Checkout team " + suffix);
        owners.assign(other.getId(), "Other team " + suffix);

        SoftwareComponent vulnerable = inventory.createSoftwareComponent("npm", "vulnerable-lib-" + suffix, "1.0");
        SoftwareComponent web = inventory.createSoftwareComponent("npm", "web-" + suffix, "1.0");
        SoftwareComponent api = inventory.createSoftwareComponent("npm", "api-" + suffix, "1.0");
        SoftwareComponent unrelated = inventory.createSoftwareComponent("npm", "unrelated-lib-" + suffix, "1.0");

        // Edges added by hand hold in every service: web depends on the vulnerable component, api on web.
        inventory.addComponentDependency(web.getId(), vulnerable.getId());
        inventory.addComponentDependency(api.getId(), web.getId());

        // checkout uses the vulnerable component directly AND transitively via api.
        inventory.addServiceComponent(checkout.getId(), vulnerable.getId());
        inventory.addServiceComponent(checkout.getId(), api.getId());
        // other uses an unrelated component only.
        inventory.addServiceComponent(other.getId(), unrelated.getId());

        // One row per service and version: the direct use wins over the one through api.
        mvc.perform(usedBy("npm", "vulnerable-lib-" + suffix).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ecosystem").value("npm"))
                .andExpect(jsonPath("$.name").value("vulnerable-lib-" + suffix))
                .andExpect(jsonPath("$.uses.length()").value(1))
                .andExpect(jsonPath("$.uses[0].service.id").value(checkout.getId().toString()))
                .andExpect(jsonPath("$.uses[0].component.id").value(vulnerable.getId().toString()))
                .andExpect(jsonPath("$.uses[0].direct").value(true))
                .andExpect(jsonPath("$.uses[0].through").isEmpty())
                .andExpect(jsonPath("$.uses[0].owners.length()").value(1))
                .andExpect(jsonPath("$.uses[0].owners[0].id").value(checkoutOwner.getId().toString()));

        // web is reached only through api; unrelated is not reachable from checkout at all.
        mvc.perform(usedBy("NPM", "WEB-" + suffix).with(role("SERVICE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uses.length()").value(1))
                .andExpect(jsonPath("$.uses[0].service.id").value(checkout.getId().toString()))
                .andExpect(jsonPath("$.uses[0].direct").value(false))
                .andExpect(jsonPath("$.uses[*].through[*].id", contains(api.getId().toString())));

        mvc.perform(usedBy("npm", "unrelated-lib-" + suffix).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uses[*].service.id", contains(other.getId().toString())));
    }

    @Test
    void usedByCoversEveryVersionOfThePackage() throws Exception {
        String suffix = UUID.randomUUID().toString();
        BusinessService legacy = inventory.createBusinessService("Legacy " + suffix, BusinessCriticality.LOW);
        BusinessService modern = inventory.createBusinessService("Modern " + suffix, BusinessCriticality.HIGH);
        SoftwareComponent old = inventory.createSoftwareComponent("npm", "shared-" + suffix, "1.5.0");
        SoftwareComponent current = inventory.createSoftwareComponent("npm", "shared-" + suffix, "1.18.0");
        inventory.createSoftwareComponent("npm", "shared-" + suffix, "2.0.0"); // in the inventory, used by nobody
        inventory.addServiceComponent(legacy.getId(), old.getId());
        inventory.addServiceComponent(modern.getId(), current.getId());
        inventory.addServiceComponent(modern.getId(), old.getId());

        mvc.perform(usedBy("npm", "shared-" + suffix).with(role("COMPLIANCE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uses[*].service.name",
                        contains("Legacy " + suffix, "Modern " + suffix, "Modern " + suffix)))
                .andExpect(jsonPath("$.uses[*].component.version", contains("1.5.0", "1.5.0", "1.18.0")))
                .andExpect(jsonPath("$.uses[*].direct", contains(true, true, true)));

        mvc.perform(usedBy("npm", "nothing-by-this-name-" + suffix).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uses").isEmpty());
    }

    @Test
    void usedByReportsADiamondOnceAndTerminatesOnCycles() throws Exception {
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

        // From loopB down: loopA, top, then left or right — whichever, four components deep.
        mvc.perform(usedBy("npm", "base-" + suffix).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uses.length()").value(1))
                .andExpect(jsonPath("$.uses[0].service.id").value(service.getId().toString()))
                .andExpect(jsonPath("$.uses[0].direct").value(false))
                .andExpect(jsonPath("$.uses[0].through.length()").value(4))
                .andExpect(jsonPath("$.uses[0].through[0].id").value(loopB.getId().toString()))
                .andExpect(jsonPath("$.uses[0].through[1].id").value(loopA.getId().toString()))
                .andExpect(jsonPath("$.uses[0].through[2].id").value(top.getId().toString()))
                .andExpect(jsonPath("$.uses[0].owners.length()").value(0));
    }

    @Test
    void vendorUsedByListsServicesUsingTheVendorDirectlyWithOwners() throws Exception {
        String suffix = UUID.randomUUID().toString();
        BusinessService checkout = inventory.createBusinessService("Checkout " + suffix, BusinessCriticality.HIGH);
        BusinessService other = inventory.createBusinessService("Other " + suffix, BusinessCriticality.LOW);
        Owner owner = owners.assign(checkout.getId(), "Checkout team " + suffix);

        ThirdPartyVendor vendor = inventory.createThirdPartyVendor("Cloud vendor " + suffix);
        inventory.addServiceVendor(checkout.getId(), vendor.getId());

        mvc.perform(get("/api/inventory/vendors/{id}/used-by", vendor.getId()).with(role("COMPLIANCE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vendor.id").value(vendor.getId().toString()))
                .andExpect(jsonPath("$.services.length()").value(1))
                .andExpect(jsonPath("$.services[0].service.id").value(checkout.getId().toString()))
                .andExpect(jsonPath("$.services[0].owners[0].id").value(owner.getId().toString()));

        mvc.perform(get("/api/inventory/vendors/{id}/used-by", other.getId()).with(role("ANALYST")))
                .andExpect(status().isNotFound());
    }

    @Test
    void usedByNeedsAnInventoryReadRoleAndAPackage() throws Exception {
        UUID vendorId = inventory.createThirdPartyVendor("Auth check " + UUID.randomUUID()).getId();

        mvc.perform(usedBy("npm", "auth-check")).andExpect(status().isUnauthorized());
        mvc.perform(usedBy("npm", "auth-check").with(role("USER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/inventory/vendors/{id}/used-by", vendorId).with(role("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/inventory/components/used-by").param("ecosystem", "npm").param("name", "x")
                        .with(role("ANALYST")))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(usedBy("npm", " ").with(role("ANALYST"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/inventory/components/used-by").param("ecosystem", "npm").with(role("ANALYST")))
                .andExpect(status().isBadRequest());
    }

    private static MockHttpServletRequestBuilder usedBy(String ecosystem, String name) {
        return get("/api/inventory/components/used-by").param("ecosystem", ecosystem).param("name", name);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor role(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
