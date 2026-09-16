package com.aegis.core.ingestion.nvd;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.aegis.core.ingestion.RawEvent;
import com.aegis.core.ingestion.RawEventStore;
import com.aegis.core.ingestion.StoredRawEvent;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Drives the connector against a real local HTTP server rather than a mocked client, so the URL it
 * builds, the date format NVD requires, and the paging loop are all genuinely exercised.
 */
class NvdSourceTest {

    private static final DateTimeFormatter NVD_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private HttpServer server;
    private final List<String> requestedUris = new ArrayList<>();
    private final Deque<String> responses = new ArrayDeque<>();
    private final FakeRawEventStore store = new FakeRawEventStore();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/rest/json/cves/2.0", exchange -> {
            requestedUris.add(exchange.getRequestURI().toString());
            byte[] body = (responses.isEmpty() ? "{\"totalResults\":0,\"vulnerabilities\":[]}" : responses.poll())
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void mapsEachCveToARawEventCarryingTheUntouchedPayload() throws Exception {
        responses.add(page(2, cve("CVE-2024-1111", "2024-05-01T10:00:00.000"),
                cve("CVE-2024-2222", "2024-05-01T11:00:00.000")));

        List<RawEvent> events = source(properties()).poll();

        assertThat(events).hasSize(2);
        assertThat(events.get(0).payload()).contains("\"id\":\"CVE-2024-1111\"");
        assertThat(events.get(0).contentType()).isEqualTo("application/json");
        assertThat(events.get(0).occurredAt()).isEqualTo(Instant.parse("2024-05-01T10:00:00Z"));
    }

    @Test
    void keysEachRevisionSeparatelySoARescoredCveIsNotSwallowedAsADuplicate() throws Exception {
        responses.add(page(1, cve("CVE-2024-1111", "2024-05-01T10:00:00.000")));
        responses.add(page(1, cve("CVE-2024-1111", "2024-06-02T09:30:00.000")));

        String first = source(properties()).poll().get(0).externalId();
        String afterRevision = source(properties()).poll().get(0).externalId();

        assertThat(first).isEqualTo("CVE-2024-1111@2024-05-01T10:00:00.000");
        assertThat(afterRevision).isEqualTo("CVE-2024-1111@2024-06-02T09:30:00.000");
        assertThat(first).isNotEqualTo(afterRevision);
    }

    @Test
    void resumesFromTheStoredWatermarkMinusTheOverlap() throws Exception {
        Instant watermark = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MILLIS);
        store.watermark = watermark;
        responses.add(page(0));

        source(properties()).poll();

        assertThat(requestedStart()).isEqualTo(watermark.minus(Duration.ofMinutes(10)));
    }

    @Test
    void clampsAWindowWiderThanNvdAccepts() throws Exception {
        // A long outage leaves the watermark far behind; NVD rejects a lastMod range over 120 days.
        store.watermark = Instant.now().minus(Duration.ofDays(400));
        responses.add(page(0));

        source(properties()).poll();

        assertThat(requestedStart())
                .isCloseTo(Instant.now().minus(Duration.ofDays(120)), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void usesTheInitialWindowBeforeAnythingHasBeenIngested() throws Exception {
        responses.add(page(0));

        source(properties()).poll();

        assertThat(requestedStart())
                .isCloseTo(Instant.now().minus(Duration.ofDays(2)), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void pagesUntilEveryResultInTheWindowHasBeenFetched() throws Exception {
        responses.add(page(3, cve("CVE-1", "2024-05-01T10:00:00.000"), cve("CVE-2", "2024-05-01T10:00:00.000")));
        responses.add(page(3, cve("CVE-3", "2024-05-01T10:00:00.000")));

        List<RawEvent> events = source(properties()).poll();

        assertThat(events).hasSize(3);
        assertThat(requestedUris).hasSize(2);
        assertThat(requestedUris.get(0)).contains("startIndex=0");
        assertThat(requestedUris.get(1)).contains("startIndex=2");
    }

    @Test
    void stopsAtThePageCapRatherThanPollingForever() throws Exception {
        // Claims far more results than it will ever hand back, so only the cap ends the loop.
        responses.add(page(99, cve("CVE-1", "2024-05-01T10:00:00.000"), cve("CVE-2", "2024-05-01T10:00:00.000")));
        responses.add(page(99, cve("CVE-3", "2024-05-01T10:00:00.000"), cve("CVE-4", "2024-05-01T10:00:00.000")));
        responses.add(page(99, cve("CVE-5", "2024-05-01T10:00:00.000"), cve("CVE-6", "2024-05-01T10:00:00.000")));

        List<RawEvent> events = source(properties(2, 2, 256 * 1024)).poll();

        assertThat(requestedUris).hasSize(2);
        assertThat(events).hasSize(4);
    }

    @Test
    void skipsCvesWhosePayloadExceedsTheConfiguredCap() throws Exception {
        responses.add(page(1, cve("CVE-2024-1111", "2024-05-01T10:00:00.000")));

        List<RawEvent> events = source(properties(2, 10, 32)).poll();

        assertThat(events).isEmpty();
    }

    private NvdSource source(NvdProperties properties) {
        NvdApiClient client = new NvdApiClient(properties, new ObjectMapper(), ObservationRegistry.NOOP);
        return new NvdSource(client, store, properties);
    }

    private NvdProperties properties() {
        return properties(2, 10, 256 * 1024);
    }

    private NvdProperties properties(int resultsPerPage, int maxPages, int maxPayloadBytes) {
        return new NvdProperties(
                true,
                "http://localhost:" + server.getAddress().getPort() + "/rest/json/cves/2.0",
                "",
                Duration.ofDays(2),
                Duration.ofMinutes(10),
                resultsPerPage,
                maxPages,
                Duration.ZERO, // no politeness delay between pages in tests
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                maxPayloadBytes,
                200,
                64L * 1024 * 1024,
                true); // allow localhost in tests
    }

    /** The lastModStartDate the connector asked for, back as an instant. */
    private Instant requestedStart() {
        return LocalDateTime.parse(queryParam("lastModStartDate"), NVD_FORMAT).toInstant(ZoneOffset.UTC);
    }

    private String queryParam(String name) {
        String query = requestedUris.get(0).substring(requestedUris.get(0).indexOf('?') + 1);
        Map<String, String> params = new HashMap<>();
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            params.put(
                    URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return params.get(name);
    }

    private static String page(int totalResults, String... cves) {
        return "{\"resultsPerPage\":" + cves.length + ",\"totalResults\":" + totalResults
                + ",\"vulnerabilities\":[" + String.join(",", cves) + "]}";
    }

    private static String cve(String id, String lastModified) {
        return "{\"cve\":{\"id\":\"" + id + "\",\"lastModified\":\"" + lastModified
                + "\",\"descriptions\":[{\"lang\":\"en\",\"value\":\"example\"}]}}";
    }

    /** Only the watermark matters here; the scheduler, not the connector, does the storing. */
    private static final class FakeRawEventStore implements RawEventStore {
        private Instant watermark;

        @Override
        public boolean saveIfNew(String sourceId, RawEvent event) {
            return true;
        }

        @Override
        public List<StoredRawEvent> findUnprocessed(String sourceId, int limit) {
            return List.of();
        }

        @Override
        public void markProcessed(UUID id) {
        }

        @Override
        public void recordFailure(UUID id) {
        }

        @Override
        public Optional<Instant> latestOccurredAt(String sourceId) {
            return Optional.ofNullable(watermark);
        }
    }
}
