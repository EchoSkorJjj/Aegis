package com.aegis.core.ingestion.connectors.vendorstatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import com.aegis.core.incidents.VendorIncident;
import com.aegis.core.ingestion.connectors.feed.SyndicationFeedParser;
import com.aegis.core.ingestion.connectors.vendorstatus.VendorStatusProperties.Format;
import com.aegis.core.ingestion.connectors.vendorstatus.VendorStatusProperties.Vendor;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapping from feed entries onto vendor incidents. The XML itself — formats, id fallbacks, and the
 * XXE hardening — is {@link SyndicationFeedParser}'s contract and is asserted in its own test.
 */
class RssFeedReaderTest {

    private static final Vendor AWS = new Vendor(
            "aws", "Amazon Web Services", "https://status.aws.amazon.com/rss/all.rss", Format.RSS);

    private final RssFeedReader reader = new RssFeedReader(new SyndicationFeedParser());

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
        // AWS stamps pubDate with a zone abbreviation rather than an offset; PDT is UTC-7.
        assertThat(first.startedAt()).isEqualTo(Instant.parse("2026-09-15T10:27:10Z"));
        assertThat(first.lastUpdateAt()).isEqualTo(Instant.parse("2026-09-15T10:27:10Z"));
    }

    @Test
    void leavesStatusAndImpactUnsetBecauseRssCarriesNeither() throws Exception {
        VendorIncident incident = reader.read(fixture(), AWS).get(0);

        assertThat(incident.status()).isNull();
        assertThat(incident.impact()).isNull();
        assertThat(incident.resolvedAt()).isNull();
        assertThat(incident.resolved()).isFalse();
    }

    @Test
    void scopesExternalIdsByVendorSoTwoVendorsCannotCollide() throws Exception {
        Vendor other = new Vendor("other", "Other Vendor", "https://status.example.com/feed.rss", Format.RSS);

        assertThat(reader.read(fixture(), AWS).get(0).externalId()).startsWith("aws:");
        assertThat(reader.read(fixture(), other).get(0).externalId()).startsWith("other:");
    }

    @Test
    void fallsBackToAPlaceholderTitleWhenAnEntryHasNone() {
        String feed = """
                <?xml version="1.0"?>
                <rss version="2.0"><channel>
                  <item><link>https://status.example.com/1</link></item>
                </channel></rss>
                """;

        assertThat(reader.read(feed, AWS).get(0).title()).isEqualTo("(untitled incident)");
    }

    @Test
    void handlesAFeedWithNoItems() {
        assertThat(reader.read("<rss version=\"2.0\"><channel><title>Quiet</title></channel></rss>", AWS))
                .isEmpty();
    }
}
