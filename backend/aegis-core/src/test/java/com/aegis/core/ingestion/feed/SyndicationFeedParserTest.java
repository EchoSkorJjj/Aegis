package com.aegis.core.ingestion.feed;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared RSS/Atom parser. Every feed connector goes through this, so the XXE hardening is
 * asserted here rather than per connector.
 */
class SyndicationFeedParserTest {

    private final SyndicationFeedParser parser = new SyndicationFeedParser();

    @Test
    void readsRss2Items() {
        List<FeedEntry> entries = parser.parse("""
                <?xml version="1.0"?>
                <rss version="2.0"><channel>
                  <title>Channel title, not an entry title</title>
                  <item>
                    <title>First</title>
                    <link>https://example.com/1</link>
                    <guid isPermaLink="false">urn:1</guid>
                    <pubDate>Mon, 14 Sep 2026 11:05:00 GMT</pubDate>
                    <description>Body one.</description>
                  </item>
                  <item><title>Second</title><link>https://example.com/2</link></item>
                </channel></rss>
                """);

        assertThat(entries).hasSize(2);
        FeedEntry first = entries.get(0);
        assertThat(first.id()).isEqualTo("urn:1");
        assertThat(first.title()).isEqualTo("First");
        assertThat(first.link()).isEqualTo("https://example.com/1");
        assertThat(first.summary()).isEqualTo("Body one.");
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-09-14T11:05:00Z"));
    }

    @Test
    void readsAtomEntries() {
        List<FeedEntry> entries = parser.parse("""
                <?xml version="1.0" encoding="utf-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>Feed title</title>
                  <entry>
                    <id>urn:incident:42</id>
                    <title>Partial degradation</title>
                    <link href="https://example.com/42"/>
                    <updated>2026-09-16T02:03:10Z</updated>
                    <summary>Elevated error rates.</summary>
                  </entry>
                </feed>
                """);

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo("urn:incident:42");
            assertThat(entry.link()).isEqualTo("https://example.com/42"); // from the href attribute
            assertThat(entry.summary()).isEqualTo("Elevated error rates.");
            assertThat(entry.publishedAt()).isEqualTo(Instant.parse("2026-09-16T02:03:10Z"));
        });
    }

    @Test
    void fallsBackFromGuidToLinkThenToAStableHash() {
        List<FeedEntry> entries = parser.parse("""
                <?xml version="1.0"?>
                <rss version="2.0"><channel>
                  <item><title>Has link</title><link>https://example.com/a</link></item>
                  <item><title>Has nothing</title><pubDate>Mon, 14 Sep 2026 11:05:00 GMT</pubDate></item>
                </channel></rss>
                """);

        assertThat(entries.get(0).id()).isEqualTo("https://example.com/a");
        assertThat(entries.get(1).id()).startsWith("sha256:");
    }

    @Test
    void producesTheSameIdForTheSameEntryAcrossRePolls() {
        String feed = """
                <?xml version="1.0"?>
                <rss version="2.0"><channel>
                  <item><title>Stable</title><pubDate>Mon, 14 Sep 2026 11:05:00 GMT</pubDate></item>
                </channel></rss>
                """;

        // Otherwise every poll would look like a brand-new entry and duplicate it.
        assertThat(parser.parse(feed).get(0).id()).isEqualTo(parser.parse(feed).get(0).id());
    }

    @Test
    void flattensEscapedHtmlInSummaries() {
        List<FeedEntry> entries = parser.parse("""
                <?xml version="1.0"?>
                <rss version="2.0"><channel>
                  <item><title>T</title>
                    <description>&lt;p&gt;Bold &lt;b&gt;text&lt;/b&gt; &amp;amp; entities.&lt;/p&gt;</description>
                  </item>
                </channel></rss>
                """);

        assertThat(entries.get(0).summary()).isEqualTo("Bold text & entities.");
    }

    @Test
    void rejectsXxeRatherThanResolvingAnExternalEntity() {
        // The classic attack: a hostile feed trying to read a local file through the parser.
        assertThatThrownBy(() -> parser.parse("""
                <?xml version="1.0"?>
                <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <rss version="2.0"><channel><item><title>&xxe;</title></item></channel></rss>
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not parseable XML");
    }

    @Test
    void rejectsEntityExpansionBombs() {
        // "Billion laughs": also DOCTYPE-based, so disallowing DOCTYPE stops it before expansion.
        assertThatThrownBy(() -> parser.parse("""
                <?xml version="1.0"?>
                <!DOCTYPE lolz [
                  <!ENTITY lol "lol">
                  <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
                  <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
                ]>
                <rss version="2.0"><channel><item><title>&lol3;</title></item></channel></rss>
                """))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedXml() {
        assertThatThrownBy(() -> parser.parse("<rss><channel><item></rss>"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handlesADocumentWithNoEntries() {
        assertThat(parser.parse("<rss version=\"2.0\"><channel><title>Quiet</title></channel></rss>")).isEmpty();
    }
}
