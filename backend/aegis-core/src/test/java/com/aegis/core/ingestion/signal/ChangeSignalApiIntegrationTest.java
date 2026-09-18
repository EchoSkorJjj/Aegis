package com.aegis.core.ingestion.signal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.aegis.core.ingestion.advisory.Advisory;
import com.aegis.core.ingestion.advisory.AdvisoryStore;
import com.aegis.core.ingestion.advisory.AffectedProduct;
import com.aegis.core.ingestion.incident.VendorIncident;
import com.aegis.core.ingestion.incident.VendorIncidentStore;
import com.aegis.core.ingestion.news.NewsItem;
import com.aegis.core.ingestion.news.NewsItemStore;
import com.aegis.core.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.containsInRelativeOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the {@code change_signals} view end to end: each connector's own store writes its
 * normalised record, and the unified API projects it into one common {@link ChangeSignal} shape.
 */
class ChangeSignalApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AdvisoryStore advisories;

    @Autowired
    private VendorIncidentStore vendorIncidents;

    @Autowired
    private NewsItemStore newsItems;

    private static RequestPostProcessor role(String... roles) {
        return jwt().authorities(Arrays.stream(roles)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toArray(GrantedAuthority[]::new));
    }

    private static String unique() {
        return Long.toString(Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000));
    }

    @Test
    void requiresAnAegisDomainReadRole() throws Exception {
        mvc.perform(get("/api/change-signals"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/api/change-signals"));

        // Platform identity roles are deliberately not Aegis domain roles.
        mvc.perform(get("/api/change-signals").with(role("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        mvc.perform(get("/api/change-signals").with(role("PLATFORM_ADMIN")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/change-signals").with(role("SERVICE_OWNER"))).andExpect(status().isOk());
        mvc.perform(get("/api/change-signals").with(role("ANALYST"))).andExpect(status().isOk());
        mvc.perform(get("/api/change-signals").with(role("COMPLIANCE_ADMIN"))).andExpect(status().isOk());
    }

    @Test
    void projectsAnAdvisoryAsAVulnerabilitySignal() throws Exception {
        String cveId = "CVE-2099-" + unique();
        Advisory advisory = advisories.upsert(new Advisory(
                null, "nvd", cveId, "A critical RCE.", Instant.parse("2099-01-01T00:00:00Z"),
                Instant.parse("2099-06-01T00:00:00Z"),
                new Advisory.Cvss("3.1", "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                        new BigDecimal("9.8"), "CRITICAL"),
                List.of()));

        mvc.perform(get("/api/change-signals/" + advisory.id()).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signalType").value("VULNERABILITY"))
                .andExpect(jsonPath("$.sourceId").value("nvd"))
                .andExpect(jsonPath("$.externalId").value(cveId))
                .andExpect(jsonPath("$.title").value(cveId))
                .andExpect(jsonPath("$.summary").value("A critical RCE."))
                .andExpect(jsonPath("$.severity").value("CRITICAL"))
                // Advisories carry no source URL, so the response derives NVD's CVE permalink.
                .andExpect(jsonPath("$.url").value("https://nvd.nist.gov/vuln/detail/" + cveId));
    }

    @Test
    void projectsAVendorIncidentAsAVendorOutageSignal() throws Exception {
        String externalId = "acme:incident-" + unique();
        VendorIncident incident = vendorIncidents.upsert(new VendorIncident(
                null, "vendor-status", externalId, "acme", "Acme Cloud", "Partial outage",
                "investigating", "major", "https://status.acme.example/incidents/1",
                "Elevated error rates.", Instant.parse("2099-03-01T00:00:00Z"), null, null));

        mvc.perform(get("/api/change-signals/" + incident.id()).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signalType").value("VENDOR_OUTAGE"))
                .andExpect(jsonPath("$.sourceId").value("vendor-status"))
                .andExpect(jsonPath("$.externalId").value(externalId))
                .andExpect(jsonPath("$.title").value("Partial outage"))
                .andExpect(jsonPath("$.summary").value("Elevated error rates."))
                .andExpect(jsonPath("$.severity").value("major"))
                .andExpect(jsonPath("$.url").value("https://status.acme.example/incidents/1"));
    }

    @Test
    void projectsANewsItemAsASecurityNewsSignal() throws Exception {
        String externalId = "feed:item-" + unique();
        NewsItem item = newsItems.upsert(new NewsItem(
                null, "security-news", externalId, "feed", "Security Weekly", "New campaign observed",
                "https://news.example/" + externalId, "A phishing campaign.",
                Instant.parse("2099-04-01T00:00:00Z"), List.of("CVE-2099-1234")));

        mvc.perform(get("/api/change-signals/" + item.id()).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signalType").value("SECURITY_NEWS"))
                .andExpect(jsonPath("$.sourceId").value("security-news"))
                .andExpect(jsonPath("$.externalId").value(externalId))
                .andExpect(jsonPath("$.title").value("New campaign observed"))
                .andExpect(jsonPath("$.summary").value("A phishing campaign."))
                .andExpect(jsonPath("$.url").value("https://news.example/" + externalId));
    }

    @Test
    void listsSignalsFromEverySourceTogetherMostRecentlyOccurredFirst() throws Exception {
        // The unified view merges all three sources ordered by occurredAt desc. Asserted over this
        // test's OWN rows in relative order (not an absolute content[0]), so it is immune to what other
        // suites seed into the shared advisories/incidents/news tables — and the dates stay below the
        // far-future rows those suites pin their own list tests to, so this never displaces them.
        String tag = unique();
        Instant oldest = Instant.parse("2099-02-01T00:00:00Z");
        Instant middle = Instant.parse("2099-03-01T00:00:00Z");
        Instant newest = Instant.parse("2099-04-01T00:00:00Z");

        String cveId = "CVE-2099-" + tag;
        advisories.upsert(new Advisory(
                null, "nvd", cveId, "An advisory.", oldest, oldest,
                new Advisory.Cvss("3.1", "CVSS:3.1/AV:N", new BigDecimal("7.5"), "HIGH"), List.of()));
        String incidentExternalId = "acme:incident-" + tag;
        vendorIncidents.upsert(new VendorIncident(
                null, "vendor-status", incidentExternalId, "acme", "Acme Cloud", "Partial outage",
                "investigating", "major", null, "Degraded.", middle, null, null));
        String newsExternalId = "feed:item-" + tag;
        newsItems.upsert(new NewsItem(
                null, "security-news", newsExternalId, "feed", "Security Weekly", "A campaign observed",
                null, "Observed.", newest, List.of()));

        // Fetch the whole (small, test-seeded) list and assert our three appear newest-first among
        // themselves, ignoring any other rows interleaved between them.
        mvc.perform(get("/api/change-signals?size=100").with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].externalId",
                        containsInRelativeOrder(newsExternalId, incidentExternalId, cveId)));
    }

    @Test
    void filtersByTypeSeveritySourceSearchAndDateAndExposesFacets() throws Exception {
        // A collision-proof token so `search=` isolates exactly this test's three rows in the shared DB.
        String tag = UUID.randomUUID().toString();
        Instant when = Instant.parse("2099-05-01T00:00:00Z");

        String cveId = "CVE-2099-" + tag;
        // Severity stored lower-case on purpose — the facet endpoint must upper-case + dedupe it.
        advisories.upsert(new Advisory(
                null, "nvd", cveId, "Advisory " + tag + ".", when, when,
                new Advisory.Cvss("3.1", "CVSS:3.1/AV:N", new BigDecimal("9.1"), "critical"), List.of()));
        String incidentExternalId = "acme:incident-" + tag;
        vendorIncidents.upsert(new VendorIncident(
                null, "vendor-status", incidentExternalId, "acme", "Acme Cloud", "Outage " + tag,
                "investigating", "major", null, "Degraded.", when, null, null));
        String newsExternalId = "feed:item-" + tag;
        newsItems.upsert(new NewsItem(
                null, "security-news", newsExternalId, "feed", "Security Weekly", "News " + tag,
                null, "Observed.", when, List.of()));

        // Text search matches the external id / title, isolating our three rows across every source.
        mvc.perform(get("/api/change-signals?search=" + tag + "&size=100").with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
        // Type narrows to our advisory.
        mvc.perform(get("/api/change-signals?search=" + tag + "&type=VULNERABILITY&size=100").with(role("ANALYST")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].externalId").value(cveId));
        // Severity match is case-insensitive.
        mvc.perform(get("/api/change-signals?search=" + tag + "&severity=critical&size=100").with(role("ANALYST")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].externalId").value(cveId));
        // Source narrows to the vendor incident.
        mvc.perform(get("/api/change-signals?search=" + tag + "&source=vendor-status&size=100").with(role("ANALYST")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].externalId").value(incidentExternalId));
        // All three occurred in 2099, so a far-past upper bound excludes them.
        mvc.perform(get("/api/change-signals?search=" + tag + "&to=2000-01-01T00:00:00Z&size=100")
                        .with(role("ANALYST")))
                .andExpect(jsonPath("$.totalElements").value(0));

        // Facets expose the distinct sources + severities for the selects; severities are upper-cased
        // and deduped, so the lower-case "critical" we stored surfaces only as "CRITICAL".
        mvc.perform(get("/api/change-signals/facets").with(role("SERVICE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources", hasItem("vendor-status")))
                .andExpect(jsonPath("$.severities", hasItem("CRITICAL")))
                .andExpect(jsonPath("$.severities", not(hasItem("critical"))));
    }

    @Test
    void collapsesTheSameCveReportedByNvdAndOsvIntoOneCanonicalSignal() throws Exception {
        // The same vulnerability arrives twice: NVD keys it by the CVE, OSV by a GHSA that *aliases*
        // the CVE (so its canonicalId is the CVE). The feed must show it once, under the CVE — not two
        // near-duplicate rows the analyst has to reconcile by eye.
        String tag = UUID.randomUUID().toString();
        String cveId = "CVE-2099-" + tag;
        Instant when = Instant.parse("2099-07-01T00:00:00Z");

        advisories.upsert(new Advisory(
                null, "nvd", cveId, "NVD " + tag, when, when,
                new Advisory.Cvss("3.1", "CVSS:3.1/AV:N", new BigDecimal("9.8"), "CRITICAL"), List.of()));
        advisories.upsert(new Advisory(
                null, "osv", "GHSA-" + tag, cveId, "OSV " + tag, when, when,
                new Advisory.Cvss("3.1", "CVSS:3.1/AV:N", new BigDecimal("9.8"), "CRITICAL"), List.of()));

        // One row, projected under the canonical CVE — the GHSA duplicate is folded away by the view.
        mvc.perform(get("/api/change-signals?search=" + cveId + "&size=100").with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].signalType").value("VULNERABILITY"))
                .andExpect(jsonPath("$.content[0].title").value(cveId));
    }

    @Test
    void filtersByEcosystemFoldedAcrossTheCanonicalAliasGroupAndExposesItAsAFacet() throws Exception {
        // Same CVE from two feeds: NVD is the fresher row (so it represents the deduped signal) but
        // carries no package coordinates; OSV is the sibling that supplies the npm purl. The ecosystem
        // filter must still match on "npm" — it folds the ecosystem across the canonical alias group,
        // not just the representative row — which is the whole point of the advisory_ecosystems view.
        String tag = UUID.randomUUID().toString();
        String cveId = "CVE-2099-" + tag;
        Instant older = Instant.parse("2099-08-01T00:00:00Z");
        Instant newer = Instant.parse("2099-08-02T00:00:00Z");

        advisories.upsert(new Advisory(
                null, "nvd", cveId, "NVD " + tag, newer, newer,
                new Advisory.Cvss("3.1", "CVSS:3.1/AV:N", new BigDecimal("9.8"), "CRITICAL"), List.of()));
        advisories.upsert(new Advisory(
                null, "osv", "GHSA-" + tag, cveId, "OSV " + tag, older, older,
                new Advisory.Cvss("3.1", "CVSS:3.1/AV:N", new BigDecimal("9.8"), "CRITICAL"),
                List.of(AffectedProduct.ofPackage("npm", "axios-" + tag, null, null, null, null, "1.6.0"))));

        // The one deduped signal is kept for npm, even though its representative row (NVD) is CPE-only.
        mvc.perform(get("/api/change-signals?search=" + cveId + "&ecosystem=npm&size=100").with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value(cveId))
                .andExpect(jsonPath("$.content[0].sourceId").value("nvd")); // folded across the alias group
        // A different ecosystem excludes it (case-insensitive on the stored, lower-cased value).
        mvc.perform(get("/api/change-signals?search=" + cveId + "&ecosystem=PyPI&size=100").with(role("ANALYST")))
                .andExpect(jsonPath("$.totalElements").value(0));

        // The facet endpoint offers npm as a selectable ecosystem.
        mvc.perform(get("/api/change-signals/facets").with(role("SERVICE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ecosystems", hasItem("npm")));
    }

    @Test
    void returnsTheUniformErrorBodyForAnUnknownSignal() throws Exception {
        UUID unknownId = UUID.randomUUID();

        mvc.perform(get("/api/change-signals/" + unknownId).with(role("ANALYST")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value("/api/change-signals/" + unknownId));
    }
}
