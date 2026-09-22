package com.aegis.core.scoring;

import java.math.BigDecimal;
import java.time.Duration;
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
import com.aegis.core.news.NewsItem;
import com.aegis.core.news.NewsItemStore;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ScoringApiIntegrationTest extends AbstractInventoryIntegrationTest {

    @Autowired
    private InventoryService inventory;

    @Autowired
    private AdvisoryStore advisories;

    @Autowired
    private VendorIncidentStore incidents;

    @Autowired
    private NewsItemStore news;

    @Test
    void scoresAnAdvisoryPerServiceWithAReproducibleBreakdown() throws Exception {
        String suffix = UUID.randomUUID().toString();
        BusinessService portal = inventory.createBusinessService(
                "Portal " + suffix, BusinessCriticality.CRITICAL, ServiceExposure.INTERNET_FACING);
        Owner owner = inventory.createOwner("Web team " + suffix);
        inventory.addServiceOwner(portal.getId(), owner.getId());

        String packageName = "score-lib-" + suffix;
        SoftwareComponent vulnerable = inventory.createSoftwareComponent(
                "maven", "com.acme:" + packageName, "1.0.0");
        inventory.addServiceComponent(portal.getId(), vulnerable.getId());

        Advisory advisory = advisories.upsert(advisory(suffix, packageName, new BigDecimal("10.0")));

        // CVSS 10.0 on an internet-facing, business-critical service is the worst case: 1 x 1 x 1.
        mvc.perform(get("/api/scoring/change-signals/{id}", advisory.id()).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.change.signalType").value("VULNERABILITY"))
                .andExpect(jsonPath("$.severity.source").value("CVSS"))
                .andExpect(jsonPath("$.severity.cvssVersion").value("3.1"))
                .andExpect(jsonPath("$.severity.cvssBaseScore").value(10.0))
                .andExpect(jsonPath("$.severity.factor").value(1.0))
                .andExpect(jsonPath("$.services.length()").value(1))
                .andExpect(jsonPath("$.services[0].service.id").value(portal.getId().toString()))
                .andExpect(jsonPath("$.services[0].service.exposure").value("INTERNET_FACING"))
                .andExpect(jsonPath("$.services[0].score").value(100.0))
                .andExpect(jsonPath("$.services[0].tier").value("CRITICAL"))
                .andExpect(jsonPath("$.services[0].material").value(true))
                .andExpect(jsonPath("$.services[0].confidence").value("CONFIRMED"))
                .andExpect(jsonPath("$.services[0].breakdown.severityFactor").value(1.0))
                .andExpect(jsonPath("$.services[0].breakdown.exposure").value("INTERNET_FACING"))
                .andExpect(jsonPath("$.services[0].breakdown.exposureFactor").value(1.0))
                .andExpect(jsonPath("$.services[0].breakdown.criticality").value("CRITICAL"))
                .andExpect(jsonPath("$.services[0].breakdown.criticalityFactor").value(1.0))
                .andExpect(jsonPath("$.owners.length()").value(1))
                .andExpect(jsonPath("$.owners[0].owner.id").value(owner.getId().toString()))
                .andExpect(jsonPath("$.owners[0].score").value(100.0))
                .andExpect(jsonPath("$.owners[0].tier").value("CRITICAL"))
                .andExpect(jsonPath("$.owners[0].impactedServiceCount").value(1))
                .andExpect(jsonPath("$.owners[0].materialServiceCount").value(1))
                .andExpect(jsonPath("$.owners[0].topService.id").value(portal.getId().toString()));
    }

    @Test
    void ranksAnIsolatedLowTierServiceBelowAPublicCriticalOneForTheSameAdvisory() throws Exception {
        String suffix = UUID.randomUUID().toString();
        // Same owner on both, so the roll-up has to pick the worse of the two.
        Owner owner = inventory.createOwner("Shared team " + suffix);
        BusinessService exposed = inventory.createBusinessService(
                "Aaa exposed " + suffix, BusinessCriticality.CRITICAL, ServiceExposure.INTERNET_FACING);
        BusinessService hidden = inventory.createBusinessService(
                "Bbb hidden " + suffix, BusinessCriticality.LOW, ServiceExposure.ISOLATED);
        inventory.addServiceOwner(exposed.getId(), owner.getId());
        inventory.addServiceOwner(hidden.getId(), owner.getId());

        String packageName = "shared-lib-" + suffix;
        SoftwareComponent vulnerable = inventory.createSoftwareComponent(
                "maven", "com.acme:" + packageName, "1.0.0");
        inventory.addServiceComponent(exposed.getId(), vulnerable.getId());
        inventory.addServiceComponent(hidden.getId(), vulnerable.getId());

        Advisory advisory = advisories.upsert(advisory(suffix, packageName, new BigDecimal("10.0")));

        mvc.perform(get("/api/scoring/change-signals/{id}", advisory.id()).with(role("SERVICE_OWNER")))
                .andExpect(status().isOk())
                // Ordered by score, not by name — "Aaa" would sort first either way, so assert the ids.
                .andExpect(jsonPath("$.services[0].service.id").value(exposed.getId().toString()))
                .andExpect(jsonPath("$.services[0].score").value(100.0))
                .andExpect(jsonPath("$.services[1].service.id").value(hidden.getId().toString()))
                // 1.0 x 0.4 (ISOLATED) x 0.25 (LOW) = 10.0 — material, but bottom of the queue.
                .andExpect(jsonPath("$.services[1].score").value(10.0))
                .andExpect(jsonPath("$.services[1].tier").value("LOW"))
                .andExpect(jsonPath("$.owners[0].score").value(100.0))
                .andExpect(jsonPath("$.owners[0].impactedServiceCount").value(2))
                .andExpect(jsonPath("$.owners[0].topService.id").value(exposed.getId().toString()));
    }

    @Test
    void scoresAVendorOutageFromTheVendorsImpactLabel() throws Exception {
        String suffix = UUID.randomUUID().toString();
        BusinessService service = inventory.createBusinessService(
                "Checkout " + suffix, BusinessCriticality.HIGH, ServiceExposure.INTERNAL);
        ThirdPartyVendor vendor = inventory.createThirdPartyVendor("Acme Cloud " + suffix);
        inventory.addServiceVendor(service.getId(), vendor.getId());

        VendorIncident incident = incidents.upsert(new VendorIncident(
                null, "vendor-status", "acme:" + suffix, "acme-cloud-" + suffix,
                "Acme Cloud " + suffix, "API outage", "investigating", "major", null,
                "Elevated errors", Instant.now(), null, null));

        // 0.75 ("major") x 0.6 (INTERNAL) x 0.75 (HIGH) = 33.75 -> 33.8
        mvc.perform(get("/api/scoring/change-signals/{id}", incident.id()).with(role("COMPLIANCE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.change.signalType").value("VENDOR_OUTAGE"))
                .andExpect(jsonPath("$.severity.source").value("SEVERITY_LABEL"))
                .andExpect(jsonPath("$.severity.label").value("major"))
                .andExpect(jsonPath("$.severity.cvssBaseScore").doesNotExist())
                .andExpect(jsonPath("$.services[0].service.id").value(service.getId().toString()))
                .andExpect(jsonPath("$.services[0].score").value(33.8))
                .andExpect(jsonPath("$.services[0].tier").value("MEDIUM"));
    }

    @Test
    void marksATrivialAdvisoryOnALowValueServiceImmaterialWithoutHidingIt() throws Exception {
        String suffix = UUID.randomUUID().toString();
        BusinessService service = inventory.createBusinessService(
                "Scratch " + suffix, BusinessCriticality.LOW, ServiceExposure.ISOLATED);
        String packageName = "trivial-lib-" + suffix;
        SoftwareComponent vulnerable = inventory.createSoftwareComponent(
                "maven", "com.acme:" + packageName, "1.0.0");
        inventory.addServiceComponent(service.getId(), vulnerable.getId());

        Advisory advisory = advisories.upsert(advisory(suffix, packageName, new BigDecimal("2.0")));

        // 0.2 x 0.4 x 0.25 = 2.0 — under the materiality threshold, but still returned with its reason.
        mvc.perform(get("/api/scoring/change-signals/{id}", advisory.id()).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services.length()").value(1))
                .andExpect(jsonPath("$.services[0].score").value(2.0))
                .andExpect(jsonPath("$.services[0].tier").value("NEGLIGIBLE"))
                .andExpect(jsonPath("$.services[0].material").value(false))
                .andExpect(jsonPath("$.services[0].explanation").isNotEmpty())
                .andExpect(jsonPath("$.owners.length()").value(0));
    }

    @Test
    void requiresADomainReadRoleAndRejectsAnUnscorableSignal() throws Exception {
        UUID unknown = UUID.randomUUID();

        mvc.perform(get("/api/scoring/change-signals/{id}", unknown))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/scoring/change-signals/{id}", unknown).with(role("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/scoring/change-signals/{id}", unknown).with(role("ANALYST")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/api/scoring/change-signals/" + unknown));

        // A news article names no package or vendor, so there is nothing to score — matching's 400.
        String suffix = UUID.randomUUID().toString();
        NewsItem article = news.upsert(new NewsItem(
                null, "security-news", "article-" + suffix, "krebs", "Krebs on Security",
                "Something happened " + suffix, "https://example.test/" + suffix, "summary",
                Instant.now(), List.of()));
        mvc.perform(get("/api/scoring/change-signals/{id}", article.id()).with(role("ANALYST")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }


    @Test
    void rollsUpEveryScoredSignalInAWindowPerServiceAndOwner() throws Exception {
        // The integration suite shares one database, so the roll-up is narrowed with `search` to this
        // test's own signals — a global count here would break the moment another suite seeds a row.
        String tag = Long.toHexString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        Owner owner = inventory.createOwner("Rollup team " + tag);
        BusinessService exposed = inventory.createBusinessService(
                "Rollup exposed " + tag, BusinessCriticality.CRITICAL, ServiceExposure.INTERNET_FACING);
        BusinessService hidden = inventory.createBusinessService(
                "Rollup hidden " + tag, BusinessCriticality.LOW, ServiceExposure.ISOLATED);
        inventory.addServiceOwner(exposed.getId(), owner.getId());
        inventory.addServiceOwner(hidden.getId(), owner.getId());

        String packageName = "rollup-lib-" + tag;
        SoftwareComponent vulnerable = inventory.createSoftwareComponent(
                "maven", "com.acme:" + packageName, "1.0.0");
        inventory.addServiceComponent(exposed.getId(), vulnerable.getId());
        inventory.addServiceComponent(hidden.getId(), vulnerable.getId());

        // Two advisories on the same component: one severe, one mild.
        advisories.upsert(taggedAdvisory(tag, "1", packageName, new BigDecimal("10.0")));
        advisories.upsert(taggedAdvisory(tag, "2", packageName, new BigDecimal("4.0")));

        mvc.perform(get("/api/scoring/rollups")
                        .param("search", tag)
                        .param("from", Instant.now().minus(Duration.ofDays(1)).toString())
                        .with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signalsConsidered").value(2))
                .andExpect(jsonPath("$.signalsImpacting").value(2))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.window.maxSignals").value(200))
                .andExpect(jsonPath("$.services.length()").value(2))
                // Highest-scoring service first, and each reports its worst signal, not a total.
                .andExpect(jsonPath("$.services[0].service.id").value(exposed.getId().toString()))
                .andExpect(jsonPath("$.services[0].topScore").value(100.0))
                .andExpect(jsonPath("$.services[0].tier").value("CRITICAL"))
                .andExpect(jsonPath("$.services[0].signalCount").value(2))
                .andExpect(jsonPath("$.services[0].materialSignalCount").value(2))
                .andExpect(jsonPath("$.services[0].topSignal.externalId").value("CVE-2099-" + tag + "1"))
                .andExpect(jsonPath("$.services[1].service.id").value(hidden.getId().toString()))
                .andExpect(jsonPath("$.services[1].topScore").value(10.0))
                // 0.4 x 0.25 x 0.4 = 4.0, under the threshold, so only one of its two signals is material.
                .andExpect(jsonPath("$.services[1].materialSignalCount").value(1))
                .andExpect(jsonPath("$.owners[0].owner.id").value(owner.getId().toString()))
                .andExpect(jsonPath("$.owners[0].topScore").value(100.0))
                .andExpect(jsonPath("$.owners[0].impactedServiceCount").value(2))
                .andExpect(jsonPath("$.owners[0].signalCount").value(4))
                .andExpect(jsonPath("$.owners[0].topService.id").value(exposed.getId().toString()));
    }

    @Test
    void boundsTheRollupAndReportsThatItWasTruncated() throws Exception {
        String tag = Long.toHexString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        BusinessService service = inventory.createBusinessService(
                "Bounded " + tag, BusinessCriticality.HIGH, ServiceExposure.INTERNAL);
        String packageName = "bounded-lib-" + tag;
        SoftwareComponent vulnerable = inventory.createSoftwareComponent(
                "maven", "com.acme:" + packageName, "1.0.0");
        inventory.addServiceComponent(service.getId(), vulnerable.getId());
        advisories.upsert(taggedAdvisory(tag, "1", packageName, new BigDecimal("10.0")));
        advisories.upsert(taggedAdvisory(tag, "2", packageName, new BigDecimal("10.0")));

        mvc.perform(get("/api/scoring/rollups")
                        .param("search", tag)
                        .param("limit", "1")
                        .with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signalsConsidered").value(1))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.services.length()").value(1));
    }

    @Test
    void theRollupNeedsADomainReadRoleToo() throws Exception {
        mvc.perform(get("/api/scoring/rollups"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/scoring/rollups").with(role("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/scoring/rollups").param("limit", "1").with(role("SERVICE_OWNER")))
                .andExpect(status().isOk());
    }

    private static Advisory taggedAdvisory(
            String tag, String ordinal, String packageName, BigDecimal baseScore) {
        return new Advisory(
                null, "nvd", "CVE-2099-" + tag + ordinal, "rollup advisory", Instant.now(), Instant.now(),
                new Advisory.Cvss("3.1", null, baseScore, "CRITICAL"),
                List.of(new AffectedProduct(
                        "cpe:2.3:a:acme:" + packageName + ":*:*:*:*:*:*:*:*",
                        "a", "acme", packageName, null, null, null, null, null)));
    }

    private static Advisory advisory(String suffix, String packageName, BigDecimal baseScore) {
        String cve = "CVE-2099-" + Math.abs(UUID.randomUUID().getMostSignificantBits());
        return new Advisory(
                null, "nvd", cve, "scoring test advisory " + suffix, Instant.now(), Instant.now(),
                new Advisory.Cvss("3.1", "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H", baseScore, "CRITICAL"),
                List.of(new AffectedProduct(
                        "cpe:2.3:a:acme:" + packageName + ":*:*:*:*:*:*:*:*",
                        "a", "acme", packageName, null, null, null, null, null)));
    }

    private static RequestPostProcessor role(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
