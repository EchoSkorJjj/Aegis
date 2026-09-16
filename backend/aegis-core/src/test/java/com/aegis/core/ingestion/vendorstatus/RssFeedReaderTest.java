package com.aegis.core.ingestion.vendorstatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import com.aegis.core.ingestion.incident.VendorIncident;
import com.aegis.core.ingestion.vendorstatus.VendorStatusProperties.Format;
import com.aegis.core.ingestion.vendorstatus.VendorStatusProperties.Vendor;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RssFeedReaderTest {

    private static final Vendor AWS = new Vendor(
            "aws", "Amazon Web Services", "https://status.aws.amazon.com/rss/all.rss", Format.RSS);

    private final RssFeedReader reader = new RssFeedReader();

    private static String fixture() throws Exception {
        return new ClassPathResource("vendorstatus/aws-status.rss")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void normalisesRssItemsIncludingCdataBodies() throws Exception {
        List<VendorIncident> incidents = reader.read(fixture(), AWS);

        assertThat(incidents).hasSize(2);
        VendorIncident first = incidents.get(0);
        assertThat(first.vendorKey()).isEqualTo("aws");
        assertThat(first.vendorName()).isEqualTo("Amazon Web Services");
        assertThat(first.title()).isEqualTo("Service disruption: Increased Error Rates");
        assertThat(first.summary()).startsWith("We are providing an update on the disruption");
        assertThat(first.url()).isEqualTo("https://status.aws.amazon.com/");
        assertThat(first.externalId())
                .isEqualTo("aws:https://status.aws.amazon.com/#multipleservices-me-central-1_1789468030");
        // Zone-abbreviation pubDate; PDT is UTC-7.
        assertThat(first.startedAt()).isEqualTo(Instant.parse("2026-09-15T10:27:10Z"));
        assertThat(first.lastUpdateAt()).isEqualTo(Instant.parse("2026-09-15T10:27:10Z"));
    }

    @Test
    void leavesStatusAndImpactUnsetBecauseRssCarriesNeither() throws Exception {
        VendorIncident incident = reader.read(fixture(), AWS).get(0);

        assertThat(incident.status()).isNull();
        assertThat(incident.impact()).isNull();
        assertThat(incident.resolvedAt()).isNull();
    }

    @Test
    void fallsBackToAStableHashWhenAnItemHasNoGuid() throws Exception {
        // The second fixture item has no <guid>, so the id falls back to its link.
        VendorIncident incident = reader.read(fixture(), AWS).get(1);

        assertThat(incident.externalId()).isEqualTo("aws:https://status.aws.amazon.com/");
        // Re-reading the same document must produce the same id, or every poll would duplicate it.
        assertThat(reader.read(fixture(), AWS).get(1).externalId()).isEqualTo(incident.externalId());
    }

    @Test
    void hashesTheEntryWhenItHasNeitherGuidNorLink() {
        String feed = """
                <?xml version="1.0"?>
                <rss version="2.0"><channel>
                  <item><title>No identifiers at all</title><pubDate>Mon, 14 Sep 2026 11:05:00 GMT</pubDate></item>
                </channel></rss>
                """;

        String externalId = reader.read(feed, AWS).get(0).externalId();

        assertThat(externalId).startsWith("aws:sha256:");
        assertThat(reader.read(feed, AWS).get(0).externalId()).isEqualTo(externalId);
    }

    @Test
    void readsAtomEntriesToo() {
        String feed = """
                <?xml version="1.0" encoding="utf-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>Vendor status</title>
                  <entry>
                    <id>urn:incident:42</id>
                    <title>Partial degradation</title>
                    <link href="https://status.example.com/incidents/42"/>
                    <updated>2026-09-16T02:03:10Z</updated>
                    <summary>Some customers may see elevated error rates.</summary>
                  </entry>
                </feed>
                """;

        VendorIncident incident = reader.read(feed, AWS).get(0);

        assertThat(incident.externalId()).isEqualTo("aws:urn:incident:42");
        assertThat(incident.title()).isEqualTo("Partial degradation");
        assertThat(incident.url()).isEqualTo("https://status.example.com/incidents/42");
        assertThat(incident.summary()).isEqualTo("Some customers may see elevated error rates.");
        assertThat(incident.startedAt()).isEqualTo(Instant.parse("2026-09-16T02:03:10Z"));
    }

    @Test
    void rejectsXxeRatherThanResolvingAnExternalEntity() {
        // The classic attack: a hostile status page trying to read a local file through the parser.
        String hostile = """
                <?xml version="1.0"?>
                <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <rss version="2.0"><channel>
                  <item><title>&xxe;</title></item>
                </channel></rss>
                """;

        assertThatThrownBy(() -> reader.read(hostile, AWS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not parseable XML");
    }

    @Test
    void rejectsEntityExpansionBombs() {
        // "Billion laughs": also DOCTYPE-based, so disallowing DOCTYPE stops it before expansion.
        String bomb = """
                <?xml version="1.0"?>
                <!DOCTYPE lolz [
                  <!ENTITY lol "lol">
                  <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
                  <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
                ]>
                <rss version="2.0"><channel><item><title>&lol3;</title></item></channel></rss>
                """;

        assertThatThrownBy(() -> reader.read(bomb, AWS)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedXml() {
        assertThatThrownBy(() -> reader.read("<rss><channel><item></rss>", AWS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handlesAFeedWithNoItems() {
        assertThat(reader.read("<rss version=\"2.0\"><channel><title>Quiet</title></channel></rss>", AWS))
                .isEmpty();
    }
}
