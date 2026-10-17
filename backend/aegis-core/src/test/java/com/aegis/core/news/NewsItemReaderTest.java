package com.aegis.core.news;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import com.aegis.core.ingestion.connectors.feed.SyndicationFeedParser;
import com.aegis.core.news.NewsProperties.Feed;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class NewsItemReaderTest {

    private static final Feed HACKER_NEWS = new Feed(
            "thehackernews", "The Hacker News", "https://feeds.feedburner.com/TheHackersNews");

    private final NewsItemReader reader = new NewsItemReader(new SyndicationFeedParser());

    private static String fixture() throws Exception {
        return new ClassPathResource("news/security-news.rss").getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void normalisesArticles() throws Exception {
        NewsItem item = reader.read(fixture(), HACKER_NEWS).get(0);

        assertThat(item.id()).isNull(); // assigned by the store on first write
        assertThat(item.sourceId()).isEqualTo("security-news");
        assertThat(item.externalId()).isEqualTo("thehackernews:tag:thehackernews.com,2021:post-1");
        assertThat(item.feedKey()).isEqualTo("thehackernews");
        assertThat(item.feedName()).isEqualTo("The Hacker News");
        assertThat(item.title()).isEqualTo("Critical Apache Log4j Flaw CVE-2021-44228 Under Active Exploitation");
        assertThat(item.url()).isEqualTo("https://thehackernews.com/2021/12/log4shell.html");
        assertThat(item.publishedAt()).isEqualTo(Instant.parse("2021-12-10T08:15:00Z"));
    }

    @Test
    void flattensEscapedHtmlInTheSummary() throws Exception {
        NewsItem item = reader.read(fixture(), HACKER_NEWS).get(0);

        assertThat(item.summary())
                .doesNotContain("<p>", "&lt;", "<b>")
                .contains("Attackers are actively exploiting CVE-2021-44228");
    }

    @Test
    void extractsCveIdentifiersFromTitleAndSummary() throws Exception {
        NewsItem item = reader.read(fixture(), HACKER_NEWS).get(0);

        // Title gives the first; the summary adds a lower-case one, which is normalised upward.
        assertThat(item.cveReferences()).containsExactly("CVE-2021-44228", "CVE-2021-45046");
    }

    @Test
    void leavesArticlesThatNameNoVulnerabilityWithoutReferences() throws Exception {
        NewsItem item = reader.read(fixture(), HACKER_NEWS).get(1);

        assertThat(item.cveReferences()).isEmpty();
        // Still ingested — it simply carries no join for matching.
        assertThat(item.title()).startsWith("Researchers Detail New Phishing Campaign");
    }

    @Test
    void deduplicatesRepeatedIdentifiersKeepingFirstAppearanceOrder() throws Exception {
        NewsItem item = reader.read(fixture(), HACKER_NEWS).get(2);

        assertThat(item.cveReferences()).containsExactly("CVE-2026-1111", "CVE-2026-2222");
    }

    @Test
    void matchesOnlyWellFormedCveIdentifiers() {
        assertThat(NewsItemReader.extractCveIds("CVE-2021-44228 cve-2021-45046 CVE-2026-1234567"))
                .containsExactly("CVE-2021-44228", "CVE-2021-45046", "CVE-2026-1234567");

        // Too few digits, missing parts, or embedded in a longer token: not identifiers.
        assertThat(NewsItemReader.extractCveIds("CVE-2021-123 CVE-21-44228 CVE- XCVE-2021-44228X"))
                .isEmpty();
    }

    @Test
    void capsTheNumberOfIdentifiersOneArticleCanContribute() {
        StringBuilder listicle = new StringBuilder();
        for (int i = 1000; i < 1000 + NewsItemReader.MAX_CVE_REFERENCES + 20; i++) {
            listicle.append("CVE-2026-").append(i).append(' ');
        }

        assertThat(NewsItemReader.extractCveIds(listicle.toString()))
                .hasSize(NewsItemReader.MAX_CVE_REFERENCES);
    }

    @Test
    void handlesNullText() {
        assertThat(NewsItemReader.extractCveIds(null, "CVE-2021-44228")).containsExactly("CVE-2021-44228");
    }

    @Test
    void scopesExternalIdsByFeedSoTwoPublishersCannotCollide() throws Exception {
        Feed other = new Feed("krebs", "Krebs on Security", "https://krebsonsecurity.com/feed/");

        List<NewsItem> hackerNews = reader.read(fixture(), HACKER_NEWS);
        List<NewsItem> krebs = reader.read(fixture(), other);

        assertThat(hackerNews.get(0).externalId()).startsWith("thehackernews:");
        assertThat(krebs.get(0).externalId()).startsWith("krebs:");
    }
}
