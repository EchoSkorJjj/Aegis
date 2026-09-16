package com.aegis.core.ingestion.signal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.aegis.core.ingestion.advisory.Advisory;
import com.aegis.core.ingestion.advisory.AdvisoryStore;
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
                .andExpect(jsonPath("$.severity").value("CRITICAL"));
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
        // Far enough in the future to reliably sort to the top regardless of what other tests seeded.
        Instant top = Instant.parse("2100-01-01T00:00:00Z");
        String cveId = "CVE-2100-" + unique();
        advisories.upsert(new Advisory(
                null, "nvd", cveId, "Listed at the top.", top, top,
                new Advisory.Cvss("3.1", "CVSS:3.1/AV:N", new BigDecimal("7.5"), "HIGH"), List.of()));

        mvc.perform(get("/api/change-signals").with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].signalType").value("VULNERABILITY"))
                .andExpect(jsonPath("$.content[0].externalId").value(cveId));
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
