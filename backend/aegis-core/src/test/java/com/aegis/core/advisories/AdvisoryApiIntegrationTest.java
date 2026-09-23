package com.aegis.core.advisories;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
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

class AdvisoryApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AdvisoryStore advisories;

    private static RequestPostProcessor role(String... roles) {
        return jwt().authorities(Arrays.stream(roles)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toArray(GrantedAuthority[]::new));
    }

    /**
     * The suite shares one database, so each test seeds its own CVE id and passes the revision
     * timestamp it needs. Assertions target these own rows (by id and by relative order), never an
     * absolute list position, so rows seeded by other suites cannot perturb them.
     */
    private Advisory seedAdvisory(String cveId, Instant lastModified) {
        return advisories.upsert(new Advisory(
                null,
                "nvd",
                cveId,
                "Seeded for the advisory API test.",
                Instant.parse("2099-01-01T00:00:00Z"),
                lastModified,
                new Advisory.Cvss("3.1", "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H",
                        new BigDecimal("10.0"), "CRITICAL"),
                List.of(new AffectedProduct(
                        "cpe:2.3:a:apache:log4j:*:*:*:*:*:*:*:*", "a", "apache", "log4j",
                        null, "2.0", null, null, "2.3.1"))));
    }

    private static String uniqueCveId() {
        return "CVE-2099-" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000);
    }

    @Test
    void requiresAnAegisDomainReadRole() throws Exception {
        mvc.perform(get("/api/advisories"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/api/advisories"));

        // Platform identity roles are deliberately not Aegis domain roles.
        mvc.perform(get("/api/advisories").with(role("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        mvc.perform(get("/api/advisories").with(role("PLATFORM_ADMIN")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/advisories").with(role("SERVICE_OWNER"))).andExpect(status().isOk());
        mvc.perform(get("/api/advisories").with(role("ANALYST"))).andExpect(status().isOk());
        mvc.perform(get("/api/advisories").with(role("COMPLIANCE_ADMIN"))).andExpect(status().isOk());
    }

    @Test
    void listsAdvisoriesMostRecentlyRevisedFirstWithTheirAffectedProducts() throws Exception {
        String newerCve = uniqueCveId();
        String olderCve = uniqueCveId();
        seedAdvisory(olderCve, Instant.parse("2099-01-01T00:00:00Z"));
        seedAdvisory(newerCve, Instant.parse("2099-12-31T00:00:00Z"));

        // Ordering asserted over this test's own two rows (relative order); the projection — including
        // the batched affected-products load that is unique to the list path — asserted on the newer
        // row located by id. Immune to whatever else shares the database.
        mvc.perform(get("/api/advisories?size=100").with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].externalId", containsInRelativeOrder(newerCve, olderCve)))
                .andExpect(jsonPath("$.content[?(@.externalId=='" + newerCve + "')].sourceId", hasItem("nvd")))
                .andExpect(jsonPath("$.content[?(@.externalId=='" + newerCve + "')].cvssSeverity", hasItem("CRITICAL")))
                .andExpect(jsonPath("$.content[?(@.externalId=='" + newerCve + "')].affected[0].vendor",
                        hasItem("apache")))
                .andExpect(jsonPath("$.content[?(@.externalId=='" + newerCve + "')].affected[0].product",
                        hasItem("log4j")));
    }

    @Test
    void returnsOneAdvisoryByFeedAndFeedIdentifier() throws Exception {
        String cveId = uniqueCveId();
        seedAdvisory(cveId, Instant.parse("2099-01-02T00:00:00Z"));

        mvc.perform(get("/api/advisories/nvd/" + cveId).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalId").value(cveId))
                .andExpect(jsonPath("$.cvssVersion").value("3.1"))
                .andExpect(jsonPath("$.affected[0].versionStartIncluding").value("2.0"))
                .andExpect(jsonPath("$.affected[0].versionEndExcluding").value("2.3.1"))
                .andExpect(jsonPath("$.affected[0].version").isEmpty())
                .andExpect(jsonPath("$.affected[0].allVersions").value(false));
    }

    @Test
    void returnsTheUniformErrorBodyForAnUnknownAdvisory() throws Exception {
        mvc.perform(get("/api/advisories/nvd/CVE-1999-00000").with(role("ANALYST")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value("/api/advisories/nvd/CVE-1999-00000"));
    }

    @Test
    void reIngestingARevisedAdvisoryUpdatesItInPlaceAndReplacesItsAffectedProducts() {
        String cveId = uniqueCveId();
        Advisory first = seedAdvisory(cveId, Instant.parse("2099-01-02T00:00:00Z"));

        Advisory revised = advisories.upsert(new Advisory(
                null,
                "nvd",
                cveId,
                "Rescored after analysis.",
                Instant.parse("2099-01-01T00:00:00Z"),
                Instant.parse("2099-02-01T00:00:00Z"),
                new Advisory.Cvss("3.1", "CVSS:3.1/AV:L/AC:H/PR:H/UI:R/S:U/C:L/I:L/A:L",
                        new BigDecimal("3.9"), "LOW"),
                List.of(new AffectedProduct(
                        "cpe:2.3:a:apache:log4j:2.15.0:*:*:*:*:*:*:*", "a", "apache", "log4j",
                        "2.15.0", null, null, null, null))));

        // Same record, not a second one: the advisory id is stable across revisions.
        assertThat(revised.id()).isEqualTo(first.id());

        Advisory stored = advisories.findByExternalId("nvd", cveId).orElseThrow();
        assertThat(stored.description()).isEqualTo("Rescored after analysis.");
        assertThat(stored.cvss().severity()).isEqualTo("LOW");
        // The superseded range is gone rather than left behind alongside the new tuple.
        assertThat(stored.affected())
                .singleElement()
                .satisfies(affected -> {
                    assertThat(affected.version()).isEqualTo("2.15.0");
                    assertThat(affected.versionStartIncluding()).isNull();
                });
    }
}
