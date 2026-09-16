package com.aegis.core.ingestion.news;

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

class NewsItemApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NewsItemStore items;

    private static RequestPostProcessor role(String... roles) {
        return jwt().authorities(Arrays.stream(roles)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toArray(GrantedAuthority[]::new));
    }

    /** The suite shares one database, so each test seeds its own article id and CVE. */
    private NewsItem seedItem(String externalId, Instant publishedAt, List<String> cves) {
        return items.upsert(new NewsItem(
                null,
                "security-news",
                externalId,
                "thehackernews",
                "The Hacker News",
                "Critical Apache Log4j Flaw Under Active Exploitation",
                "https://thehackernews.com/2021/12/log4shell.html",
                "Attackers are actively exploiting the flaw.",
                publishedAt,
                cves));
    }

    private static String uniqueExternalId() {
        return "thehackernews:test-" + UUID.randomUUID();
    }

    @Test
    void requiresAnAegisDomainReadRole() throws Exception {
        mvc.perform(get("/api/news"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/api/news"));

        // Platform identity roles are deliberately not Aegis domain roles.
        mvc.perform(get("/api/news").with(role("USER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/news").with(role("PLATFORM_ADMIN"))).andExpect(status().isForbidden());

        mvc.perform(get("/api/news").with(role("SERVICE_OWNER"))).andExpect(status().isOk());
        mvc.perform(get("/api/news").with(role("ANALYST"))).andExpect(status().isOk());
        mvc.perform(get("/api/news").with(role("COMPLIANCE_ADMIN"))).andExpect(status().isOk());
    }

    @Test
    void listsArticlesMostRecentlyPublishedFirst() throws Exception {
        String newer = uniqueExternalId();
        String older = uniqueExternalId();
        seedItem(older, Instant.parse("2099-01-01T00:00:00Z"), List.of("CVE-2021-45046"));
        seedItem(newer, Instant.parse("2099-12-31T00:00:00Z"), List.of("CVE-2021-44228"));

        // Ordering asserted over this test's own two rows (relative order); the projection — including
        // the batched CVE-reference load unique to the list path — on the newer row located by id.
        // Immune to whatever else shares the database.
        mvc.perform(get("/api/news?size=100").with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].externalId", containsInRelativeOrder(newer, older)))
                .andExpect(jsonPath("$.content[?(@.externalId=='" + newer + "')].feedName",
                        hasItem("The Hacker News")))
                .andExpect(jsonPath("$.content[?(@.externalId=='" + newer + "')].cveReferences[0]",
                        hasItem("CVE-2021-44228")));
    }

    @Test
    void filtersArticlesByTheCveTheyMention() throws Exception {
        String matching = uniqueExternalId();
        String unrelated = uniqueExternalId();
        String cve = "CVE-2099-" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 90000 + 10000);
        seedItem(matching, Instant.parse("2099-06-01T00:00:00Z"), List.of(cve));
        seedItem(unrelated, Instant.parse("2099-06-02T00:00:00Z"), List.of());

        mvc.perform(get("/api/news").param("cve", cve).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].externalId").value(matching));

        // Case-insensitive: feeds spell identifiers both ways.
        mvc.perform(get("/api/news").param("cve", cve.toLowerCase(java.util.Locale.ROOT)).with(role("ANALYST")))
                .andExpect(jsonPath("$.totalElements").value(1));

        mvc.perform(get("/api/news").param("cve", "CVE-1999-0001").with(role("ANALYST")))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void returnsOneArticleByFeedAndFeedIdentifier() throws Exception {
        String externalId = uniqueExternalId();
        seedItem(externalId, Instant.parse("2099-01-02T00:00:00Z"), List.of("CVE-2021-44228"));

        mvc.perform(get("/api/news/security-news/" + externalId).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalId").value(externalId))
                .andExpect(jsonPath("$.url").value("https://thehackernews.com/2021/12/log4shell.html"));
    }

    @Test
    void returnsTheUniformErrorBodyForAnUnknownArticle() throws Exception {
        mvc.perform(get("/api/news/security-news/nope:missing").with(role("ANALYST")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    void reIngestingAnEditedArticleUpdatesItInPlace() {
        String externalId = uniqueExternalId();
        NewsItem first = seedItem(externalId, Instant.parse("2099-01-02T00:00:00Z"), List.of("CVE-2021-44228"));

        NewsItem edited = seedItem(externalId, Instant.parse("2099-01-02T00:00:00Z"), List.of("CVE-2021-45105"));

        assertThat(edited.id()).isEqualTo(first.id());
        assertThat(items.findByExternalId("security-news", externalId).orElseThrow().cveReferences())
                .containsExactly("CVE-2021-45105");
    }
}
