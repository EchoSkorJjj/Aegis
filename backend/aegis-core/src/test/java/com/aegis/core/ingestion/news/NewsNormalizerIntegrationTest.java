package com.aegis.core.ingestion.news;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.aegis.core.ingestion.RawEvent;
import com.aegis.core.ingestion.RawEventStore;
import com.aegis.core.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The connector's second half end to end: a captured news document becomes normalised articles with
 * their CVE references, and the raw event is marked processed so the next drain does not redo it.
 *
 * <p>The feed scheduler is switched off and the drain interval pushed out, so nothing runs in the
 * background while the test asserts — the drain is invoked directly.
 */
@TestPropertySource(properties = {
        "aegis.ingestion.news.enabled=true",
        "aegis.ingestion.enabled=false",
        "aegis.ingestion.news.normalize-interval=PT30M",
        "aegis.ingestion.news.feeds[0].key=thehackernews",
        "aegis.ingestion.news.feeds[0].name=The Hacker News",
        "aegis.ingestion.news.feeds[0].url=https://example.invalid/feed"
})
class NewsNormalizerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NewsNormalizer normalizer;

    @Autowired
    private RawEventStore rawEvents;

    @Autowired
    private NewsItemStore items;

    private static String fixture() throws Exception {
        return new ClassPathResource("news/security-news.rss").getContentAsString(StandardCharsets.UTF_8);
    }

    private void capture(String feedKey, String payload) {
        rawEvents.saveIfNew(NewsSource.SOURCE_ID, new RawEvent(
                feedKey + "@" + UUID.randomUUID(), "application/rss+xml", payload, null));
    }

    @Test
    void turnsACapturedFeedIntoArticlesWithTheirCveReferences() throws Exception {
        capture("thehackernews", fixture());

        assertThat(normalizer.drain()).isPositive();

        NewsItem item = items.findByExternalId(
                NewsSource.SOURCE_ID, "thehackernews:tag:thehackernews.com,2021:post-1").orElseThrow();
        assertThat(item.id()).isNotNull();
        assertThat(item.feedName()).isEqualTo("The Hacker News");
        assertThat(item.title()).startsWith("Critical Apache Log4j Flaw");
        assertThat(item.publishedAt()).isEqualTo(Instant.parse("2021-12-10T08:15:00Z"));
        assertThat(item.cveReferences()).containsExactly("CVE-2021-44228", "CVE-2021-45046");

        // Drained, so a second pass has nothing left to do.
        assertThat(rawEvents.findUnprocessed(NewsSource.SOURCE_ID, 100)).isEmpty();
        assertThat(normalizer.drain()).isZero();
    }

    @Test
    void findsArticlesByTheCveTheyMention() throws Exception {
        capture("thehackernews", fixture());
        normalizer.drain();

        assertThat(items.findPageByCveReference("CVE-2021-44228", PageRequest.of(0, 20)).getContent())
                .extracting(NewsItem::title)
                .anySatisfy(title -> assertThat(title).startsWith("Critical Apache Log4j Flaw"));

        // Case-insensitive, because feeds spell identifiers both ways.
        assertThat(items.findPageByCveReference("cve-2021-44228", PageRequest.of(0, 20)).getTotalElements())
                .isPositive();

        assertThat(items.findPageByCveReference("CVE-1999-0001", PageRequest.of(0, 20)).getContent()).isEmpty();
    }

    @Test
    void reReadingAnEditedArticleUpdatesTheSameRowAndItsReferences() throws Exception {
        capture("thehackernews", fixture());
        normalizer.drain();
        String externalId = "thehackernews:tag:thehackernews.com,2021:post-1";
        UUID firstId = items.findByExternalId(NewsSource.SOURCE_ID, externalId).orElseThrow().id();

        // The publisher edits the piece: same guid, a different CVE called out.
        capture("thehackernews", """
                <?xml version="1.0"?>
                <rss version="2.0"><channel>
                  <item>
                    <title>Log4Shell retrospective</title>
                    <link>https://thehackernews.com/2021/12/log4shell.html</link>
                    <guid isPermaLink="false">tag:thehackernews.com,2021:post-1</guid>
                    <pubDate>Fri, 10 Dec 2021 08:15:00 GMT</pubDate>
                    <description>Now tracking CVE-2021-45105 instead.</description>
                  </item>
                </channel></rss>
                """);
        normalizer.drain();

        NewsItem updated = items.findByExternalId(NewsSource.SOURCE_ID, externalId).orElseThrow();
        assertThat(updated.id()).isEqualTo(firstId); // same row, not a second one
        assertThat(updated.title()).isEqualTo("Log4Shell retrospective");
        // Stale references are replaced, not merged.
        assertThat(updated.cveReferences()).containsExactly("CVE-2021-45105");
    }

    @Test
    void marksAnUnparseableDocumentProcessedRatherThanBlockingTheQueue() {
        capture("thehackernews", "<rss><channel><item></rss>");
        capture("unconfigured-feed", "<rss version=\"2.0\"><channel/></rss>");

        normalizer.drain();

        assertThat(rawEvents.findUnprocessed(NewsSource.SOURCE_ID, 100)).isEmpty();
    }
}
