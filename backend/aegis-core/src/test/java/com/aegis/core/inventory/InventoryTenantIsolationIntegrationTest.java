package com.aegis.core.inventory;

import java.util.UUID;

import com.aegis.core.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tenant isolation for the inventory API (ADR §6): one organization never sees, reaches, or collides
 * with another's data. Two orgs are driven through the real HTTP path via distinct {@code org} claims.
 *
 * <p>In this suite aegis-core connects as a Postgres superuser (the Testcontainers default), so RLS is
 * bypassed and it is the store's <em>application-level</em> scoping under test here — the same
 * predicate Postgres enforces via the V9 policies when the app runs as a non-superuser.
 */
class InventoryTenantIsolationIntegrationTest extends AbstractIntegrationTest {

    private static final UUID ORG_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID ORG_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @Test
    void oneOrgNeverSeesReachesOrCollidesWithAnothersInventory() throws Exception {
        String sharedName = "Shared Service " + UUID.randomUUID();

        // Org A creates a service.
        String serviceId = idFromLocation(mvc.perform(post("/api/inventory/services")
                        .with(role(ORG_A, "ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + sharedName + "\",\"criticality\":\"HIGH\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location"));

        // Org A sees its own service by id.
        mvc.perform(get("/api/inventory/services/{id}", serviceId).with(role(ORG_A, "ANALYST")))
                .andExpect(status().isOk());

        // Org B cannot fetch it by id — 404, not 403: it does not exist in B's tenant.
        mvc.perform(get("/api/inventory/services/{id}", serviceId).with(role(ORG_B, "ANALYST")))
                .andExpect(status().isNotFound());

        // Org B does not see it in its own listing.
        mvc.perform(get("/api/inventory/services?size=200").with(role(ORG_B, "ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", not(hasItem(serviceId))));

        // Per-org uniqueness: org B may reuse the exact name A used — a different tenant, no conflict.
        mvc.perform(post("/api/inventory/services")
                        .with(role(ORG_B, "ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + sharedName + "\",\"criticality\":\"LOW\"}"))
                .andExpect(status().isCreated());

        // Cross-tenant write is refused: org B cannot attach its component to A's service (A's service
        // is not found in B's tenant, so the link is rejected 404 before anything is written).
        String bComponentId = idFromLocation(mvc.perform(post("/api/inventory/components")
                        .with(role(ORG_B, "ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ecosystem\":\"npm\",\"name\":\"iso-" + UUID.randomUUID() + "\",\"version\":\"1.0\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location"));
        mvc.perform(post("/api/inventory/services/{s}/components/{c}", serviceId, bComponentId)
                        .with(role(ORG_B, "ANALYST")))
                .andExpect(status().isNotFound());
    }

    private static String idFromLocation(String location) {
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private static RequestPostProcessor role(UUID org, String role) {
        return jwt()
                .jwt(builder -> builder.claim("org", org.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
