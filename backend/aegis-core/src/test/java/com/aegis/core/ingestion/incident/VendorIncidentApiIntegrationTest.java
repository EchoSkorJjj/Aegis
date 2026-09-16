package com.aegis.core.ingestion.incident;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import com.aegis.core.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInRelativeOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VendorIncidentApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private VendorIncidentStore incidents;

    private static RequestPostProcessor role(String... roles) {
        return jwt().authorities(Arrays.stream(roles)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toArray(GrantedAuthority[]::new));
    }

    private static String uniqueExternalId() {
        return "cloudflare:test-" + UUID.randomUUID();
    }

    /**
     * The suite shares one database, so each test seeds its own incident id. Assertions target these
     * own rows (by id and by relative order), never an absolute list position, so rows seeded by other
     * suites cannot perturb them.
     */
    private VendorIncident seedIncident(String externalId, Instant startedAt) {
        return incidents.upsert(new VendorIncident(
                null,
                "vendor-status",
                externalId,
                "cloudflare",
                "Cloudflare",
                "Network Performance Issues in Los Angeles",
                "monitoring",
                "minor",
                "https://www.cloudflarestatus.com/incidents/abc",
                "A fix has been implemented and we are monitoring the results.",
                startedAt,
                startedAt.plusSeconds(3600),
                null));
    }

    @Test
    void requiresAnAegisDomainReadRole() throws Exception {
        mvc.perform(get("/api/vendor-incidents"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/api/vendor-incidents"));

        // Platform identity roles are deliberately not Aegis domain roles.
        mvc.perform(get("/api/vendor-incidents").with(role("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/vendor-incidents").with(role("PLATFORM_ADMIN")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/vendor-incidents").with(role("SERVICE_OWNER"))).andExpect(status().isOk());
        mvc.perform(get("/api/vendor-incidents").with(role("ANALYST"))).andExpect(status().isOk());
        mvc.perform(get("/api/vendor-incidents").with(role("COMPLIANCE_ADMIN"))).andExpect(status().isOk());
    }

    @Test
    void listsIncidentsMostRecentlyStartedFirst() throws Exception {
        String newer = uniqueExternalId();
        String older = uniqueExternalId();
        seedIncident(older, Instant.parse("2099-01-01T00:00:00Z"));
        seedIncident(newer, Instant.parse("2099-12-31T00:00:00Z"));

        // Ordering asserted over this test's own two rows (relative order); the projection on the newer
        // row located by id. Immune to whatever else shares the database.
        mvc.perform(get("/api/vendor-incidents?size=100").with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].externalId", containsInRelativeOrder(newer, older)))
                .andExpect(jsonPath("$.content[?(@.externalId=='" + newer + "')].vendorKey", hasItem("cloudflare")))
                .andExpect(jsonPath("$.content[?(@.externalId=='" + newer + "')].vendorName", hasItem("Cloudflare")))
                .andExpect(jsonPath("$.content[?(@.externalId=='" + newer + "')].status", hasItem("monitoring")))
                .andExpect(jsonPath("$.content[?(@.externalId=='" + newer + "')].impact", hasItem("minor")))
                .andExpect(jsonPath("$.content[?(@.externalId=='" + newer + "')].resolved", hasItem(false)));
    }

    @Test
    void returnsOneIncidentByFeedAndFeedIdentifier() throws Exception {
        String externalId = uniqueExternalId();
        seedIncident(externalId, Instant.parse("2099-01-02T00:00:00Z"));

        mvc.perform(get("/api/vendor-incidents/vendor-status/" + externalId).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalId").value(externalId))
                .andExpect(jsonPath("$.title").value("Network Performance Issues in Los Angeles"))
                .andExpect(jsonPath("$.url").value("https://www.cloudflarestatus.com/incidents/abc"))
                .andExpect(jsonPath("$.resolvedAt").isEmpty());
    }

    @Test
    void returnsTheUniformErrorBodyForAnUnknownIncident() throws Exception {
        mvc.perform(get("/api/vendor-incidents/vendor-status/nope:missing").with(role("ANALYST")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    void reIngestingAnEditedIncidentUpdatesItInPlace() {
        String externalId = uniqueExternalId();
        VendorIncident first = seedIncident(externalId, Instant.parse("2099-01-02T00:00:00Z"));

        VendorIncident resolvedUpdate = incidents.upsert(new VendorIncident(
                null,
                "vendor-status",
                externalId,
                "cloudflare",
                "Cloudflare",
                "Network Performance Issues in Los Angeles",
                "resolved",
                "minor",
                "https://www.cloudflarestatus.com/incidents/abc",
                "This incident has been resolved.",
                Instant.parse("2099-01-02T00:00:00Z"),
                Instant.parse("2099-01-02T05:00:00Z"),
                Instant.parse("2099-01-02T05:00:00Z")));

        assertThat(resolvedUpdate.id()).isEqualTo(first.id());

        VendorIncident stored = incidents.findByExternalId("vendor-status", externalId).orElseThrow();
        assertThat(stored.status()).isEqualTo("resolved");
        assertThat(stored.summary()).isEqualTo("This incident has been resolved.");
        assertThat(stored.resolved()).isTrue();
    }
}
