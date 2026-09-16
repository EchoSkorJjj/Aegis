package com.aegis.core.ingestion.vendorstatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.aegis.core.ingestion.RawEvent;
import com.aegis.core.ingestion.RawEventStore;
import com.aegis.core.ingestion.incident.VendorIncident;
import com.aegis.core.ingestion.incident.VendorIncidentStore;
import com.aegis.core.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The connector's second half end to end: a captured status document becomes normalised incidents,
 * and the raw event is marked processed so the next drain does not redo it.
 *
 * <p>The feed scheduler is switched off and the drain interval pushed out, so nothing runs in the
 * background while the test asserts — the drain is invoked directly. Two vendors are configured here
 * rather than relying on the shipped defaults, so the test stays self-contained.
 */
@TestPropertySource(properties = {
        "aegis.ingestion.vendor-status.enabled=true",
        "aegis.ingestion.enabled=false",
        "aegis.ingestion.vendor-status.normalize-interval=PT30M",
        "aegis.ingestion.vendor-status.vendors[0].key=cloudflare",
        "aegis.ingestion.vendor-status.vendors[0].name=Cloudflare",
        "aegis.ingestion.vendor-status.vendors[0].url=https://example.invalid/api/v2/incidents.json",
        "aegis.ingestion.vendor-status.vendors[0].format=statuspage",
        "aegis.ingestion.vendor-status.vendors[1].key=aws",
        "aegis.ingestion.vendor-status.vendors[1].name=Amazon Web Services",
        "aegis.ingestion.vendor-status.vendors[1].url=https://example.invalid/rss/all.rss",
        "aegis.ingestion.vendor-status.vendors[1].format=rss"
})
class VendorStatusNormalizerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private VendorStatusNormalizer normalizer;

    @Autowired
    private RawEventStore rawEvents;

    @Autowired
    private VendorIncidentStore incidents;

    private static String fixture(String name) throws Exception {
        return new ClassPathResource("vendorstatus/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    private void capture(String vendorKey, String contentType, String payload) {
        rawEvents.saveIfNew(VendorStatusSource.SOURCE_ID, new RawEvent(
                vendorKey + "@" + UUID.randomUUID(), contentType, payload, null));
    }

    @Test
    void turnsACapturedStatuspageDocumentIntoIncidents() throws Exception {
        capture("cloudflare", "application/json", fixture("statuspage-incidents.json"));

        assertThat(normalizer.drain()).isPositive();

        VendorIncident incident = incidents
                .findByExternalId(VendorStatusSource.SOURCE_ID, "cloudflare:qc2v4gxy3gfw")
                .orElseThrow();
        assertThat(incident.id()).isNotNull();
        assertThat(incident.vendorName()).isEqualTo("Cloudflare");
        assertThat(incident.title()).isEqualTo("Network Performance Issues in Los Angeles");
        assertThat(incident.status()).isEqualTo("monitoring");
        assertThat(incident.impact()).isEqualTo("minor");
        assertThat(incident.startedAt()).isEqualTo(Instant.parse("2026-09-16T02:03:10Z"));
        assertThat(incident.resolved()).isFalse();

        // Drained, so a second pass has nothing left to do.
        assertThat(rawEvents.findUnprocessed(VendorStatusSource.SOURCE_ID, 100)).isEmpty();
        assertThat(normalizer.drain()).isZero();
    }

    @Test
    void turnsACapturedRssDocumentIntoIncidents() throws Exception {
        capture("aws", "application/rss+xml", fixture("aws-status.rss"));

        normalizer.drain();

        VendorIncident incident = incidents
                .findByExternalId(VendorStatusSource.SOURCE_ID,
                        "aws:https://status.aws.amazon.com/#multipleservices-me-central-1_1789468030")
                .orElseThrow();
        assertThat(incident.vendorName()).isEqualTo("Amazon Web Services");
        assertThat(incident.title()).isEqualTo("Service disruption: Increased Error Rates");
        // AWS stamps pubDate with a zone abbreviation rather than an offset.
        assertThat(incident.startedAt()).isEqualTo(Instant.parse("2026-09-15T10:27:10Z"));
        assertThat(incident.status()).isNull();
    }

    @Test
    void reReadingAnEditedIncidentUpdatesTheSameRow() throws Exception {
        capture("cloudflare", "application/json", fixture("statuspage-incidents.json"));
        normalizer.drain();
        UUID firstId = incidents
                .findByExternalId(VendorStatusSource.SOURCE_ID, "cloudflare:qc2v4gxy3gfw")
                .orElseThrow().id();

        // The vendor moves the incident on: same incident id, new status and a resolution.
        capture("cloudflare", "application/json", """
                {"incidents":[{"id":"qc2v4gxy3gfw","name":"Network Performance Issues in Los Angeles",
                 "status":"resolved","impact":"minor","started_at":"2026-09-16T02:03:10.000Z",
                 "updated_at":"2026-09-16T05:00:00.000Z","resolved_at":"2026-09-16T05:00:00.000Z",
                 "incident_updates":[{"status":"resolved","body":"This incident has been resolved."}]}]}
                """);
        normalizer.drain();

        VendorIncident updated = incidents
                .findByExternalId(VendorStatusSource.SOURCE_ID, "cloudflare:qc2v4gxy3gfw")
                .orElseThrow();
        assertThat(updated.id()).isEqualTo(firstId); // same row, not a second one
        assertThat(updated.status()).isEqualTo("resolved");
        assertThat(updated.resolvedAt()).isEqualTo(Instant.parse("2026-09-16T05:00:00Z"));
        assertThat(updated.resolved()).isTrue();
    }

    @Test
    void marksAnUnparseableDocumentProcessedRatherThanBlockingTheQueue() {
        capture("cloudflare", "application/json", "{not json");
        capture("unconfigured-vendor", "application/json", "{\"incidents\":[]}");

        normalizer.drain();

        assertThat(rawEvents.findUnprocessed(VendorStatusSource.SOURCE_ID, 100)).isEmpty();
    }
}
