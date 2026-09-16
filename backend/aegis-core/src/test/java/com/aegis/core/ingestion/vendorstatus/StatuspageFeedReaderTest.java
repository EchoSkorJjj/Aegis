package com.aegis.core.ingestion.vendorstatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import com.aegis.core.ingestion.incident.VendorIncident;
import com.aegis.core.ingestion.vendorstatus.VendorStatusProperties.Format;
import com.aegis.core.ingestion.vendorstatus.VendorStatusProperties.Vendor;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatuspageFeedReaderTest {

    private static final Vendor CLOUDFLARE = new Vendor(
            "cloudflare", "Cloudflare", "https://www.cloudflarestatus.com/api/v2/incidents.json",
            Format.STATUSPAGE);

    private final StatuspageFeedReader reader = new StatuspageFeedReader(new ObjectMapper());

    private static String fixture() throws Exception {
        return new ClassPathResource("vendorstatus/statuspage-incidents.json")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void normalisesAnOngoingIncident() throws Exception {
        VendorIncident incident = reader.read(fixture(), CLOUDFLARE).get(0);

        assertThat(incident.id()).isNull(); // assigned by the store on first write
        assertThat(incident.sourceId()).isEqualTo("vendor-status");
        assertThat(incident.externalId()).isEqualTo("cloudflare:qc2v4gxy3gfw");
        assertThat(incident.vendorKey()).isEqualTo("cloudflare");
        assertThat(incident.vendorName()).isEqualTo("Cloudflare");
        assertThat(incident.title()).isEqualTo("Network Performance Issues in Los Angeles");
        assertThat(incident.status()).isEqualTo("monitoring");
        assertThat(incident.impact()).isEqualTo("minor");
        assertThat(incident.url()).isEqualTo("https://www.cloudflarestatus.com/incidents/qc2v4gxy3gfw");
        assertThat(incident.startedAt()).isEqualTo(Instant.parse("2026-09-16T02:03:10Z"));
        assertThat(incident.lastUpdateAt()).isEqualTo(Instant.parse("2026-09-16T03:44:46Z"));
        assertThat(incident.resolvedAt()).isNull();
        assertThat(incident.resolved()).isFalse();
    }

    @Test
    void usesTheMostRecentUpdateAsTheSummary() throws Exception {
        VendorIncident incident = reader.read(fixture(), CLOUDFLARE).get(0);

        // Statuspage lists updates newest first.
        assertThat(incident.summary()).isEqualTo("A fix has been implemented and we are monitoring the results.");
    }

    @Test
    void normalisesAResolvedIncident() throws Exception {
        VendorIncident incident = reader.read(fixture(), CLOUDFLARE).get(1);

        assertThat(incident.externalId()).isEqualTo("cloudflare:b7k2m9pq1wxs");
        assertThat(incident.impact()).isEqualTo("critical");
        assertThat(incident.resolvedAt()).isEqualTo(Instant.parse("2026-09-10T11:30:00Z"));
        assertThat(incident.resolved()).isTrue();
    }

    @Test
    void skipsEntriesWithNoIncidentId() throws Exception {
        // The third fixture entry has no id, so nothing could key it.
        assertThat(reader.read(fixture(), CLOUDFLARE)).hasSize(2);
    }

    @Test
    void scopesExternalIdsByVendorSoTwoVendorsCannotCollide() throws Exception {
        Vendor other = new Vendor("stripe", "Stripe", "https://www.stripestatus.com/api/v2/incidents.json",
                Format.STATUSPAGE);

        List<VendorIncident> cloudflare = reader.read(fixture(), CLOUDFLARE);
        List<VendorIncident> stripe = reader.read(fixture(), other);

        assertThat(cloudflare.get(0).externalId()).isEqualTo("cloudflare:qc2v4gxy3gfw");
        assertThat(stripe.get(0).externalId()).isEqualTo("stripe:qc2v4gxy3gfw");
    }

    @Test
    void handlesAFeedWithNoIncidents() {
        assertThat(reader.read("{\"page\":{},\"incidents\":[]}", CLOUDFLARE)).isEmpty();
        assertThat(reader.read("{\"page\":{}}", CLOUDFLARE)).isEmpty();
    }

    @Test
    void ignoresUnknownFieldsSoStatuspageCanExtendItsSchema() {
        String payload = """
                {"incidents":[{"id":"x1","name":"Brief","brandNewField":{"nested":true}}]}
                """;

        assertThat(reader.read(payload, CLOUDFLARE)).singleElement()
                .satisfies(incident -> assertThat(incident.title()).isEqualTo("Brief"));
    }

    /**
     * CS203-47: resilience — a malformed document must throw (so {@code VendorStatusNormalizer} wraps
     * it as {@code UnparseableEventException} and the drainer drops it, per
     * {@code VendorStatusNormalizerIntegrationTest.marksAnUnparseableDocumentProcessedRatherThanBlockingTheQueue}),
     * never silently produce an empty or partial result.
     */
    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> reader.read("{not json", CLOUDFLARE))
                .isInstanceOf(RuntimeException.class);
    }
}
