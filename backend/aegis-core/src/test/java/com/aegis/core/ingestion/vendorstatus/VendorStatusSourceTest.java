package com.aegis.core.ingestion.vendorstatus;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.aegis.core.ingestion.RawEvent;
import com.aegis.core.ingestion.vendorstatus.VendorStatusProperties.Format;
import com.aegis.core.ingestion.vendorstatus.VendorStatusProperties.Vendor;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the connector against a real local HTTP server, so the fetch, the content-addressed
 * de-duplication id, and the per-vendor failure isolation are all genuinely exercised.
 */
class VendorStatusSourceTest {

    private HttpServer server;
    private final Map<String, Response> routes = new HashMap<>();
    private final List<String> requestedPaths = new ArrayList<>();

    private record Response(int status, String body) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requestedPaths.add(path);
            Response response = routes.getOrDefault(path, new Response(404, "not found"));
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status(), body.length);
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

    private String url(String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    private Vendor vendor(String key, String path, Format format) {
        return new Vendor(key, key.toUpperCase(Locale.ROOT), url(path), format);
    }

    private VendorStatusSource source(int maxPayloadBytes, Vendor... vendors) {
        VendorStatusProperties properties = new VendorStatusProperties(
                true, List.of(vendors), Duration.ofSeconds(2), Duration.ofSeconds(5), maxPayloadBytes, 50);
        return new VendorStatusSource(properties, ObservationRegistry.NOOP);
    }

    @Test
    void capturesEachVendorDocumentVerbatim() throws Exception {
        routes.put("/cf.json", new Response(200, "{\"incidents\":[]}"));
        routes.put("/aws.rss", new Response(200, "<rss version=\"2.0\"><channel/></rss>"));

        List<RawEvent> events = source(1024 * 1024,
                vendor("cloudflare", "/cf.json", Format.STATUSPAGE),
                vendor("aws", "/aws.rss", Format.RSS)).poll();

        assertThat(events).hasSize(2);
        assertThat(events.get(0).payload()).isEqualTo("{\"incidents\":[]}");
        assertThat(events.get(0).contentType()).isEqualTo("application/json");
        assertThat(events.get(1).contentType()).isEqualTo("application/rss+xml");
    }

    @Test
    void keysEventsByVendorAndContentSoAnUnchangedPageDeduplicates() throws Exception {
        routes.put("/cf.json", new Response(200, "{\"incidents\":[]}"));
        VendorStatusSource source = source(1024 * 1024, vendor("cloudflare", "/cf.json", Format.STATUSPAGE));

        String first = source.poll().get(0).externalId();
        String unchanged = source.poll().get(0).externalId();

        assertThat(first).startsWith("cloudflare@");
        // Same document, same id — the raw-event store will treat the re-poll as a duplicate.
        assertThat(unchanged).isEqualTo(first);

        routes.put("/cf.json", new Response(200, "{\"incidents\":[{\"id\":\"x\"}]}"));
        assertThat(source.poll().get(0).externalId()).isNotEqualTo(first);
    }

    @Test
    void oneFailingVendorDoesNotCostTheOthersTheirPoll() throws Exception {
        routes.put("/broken.json", new Response(500, "upstream exploded"));
        routes.put("/cf.json", new Response(200, "{\"incidents\":[]}"));

        List<RawEvent> events = source(1024 * 1024,
                vendor("broken", "/broken.json", Format.STATUSPAGE),
                vendor("cloudflare", "/cf.json", Format.STATUSPAGE)).poll();

        assertThat(events).singleElement()
                .satisfies(event -> assertThat(event.externalId()).startsWith("cloudflare@"));
        assertThat(requestedPaths).contains("/broken.json", "/cf.json");
    }

    @Test
    void skipsADocumentLargerThanTheConfiguredCap() throws Exception {
        routes.put("/cf.json", new Response(200, "{\"incidents\":[]}"));

        List<RawEvent> events = source(4, vendor("cloudflare", "/cf.json", Format.STATUSPAGE)).poll();

        assertThat(events).isEmpty();
    }

    @Test
    void skipsAnEmptyDocument() throws Exception {
        routes.put("/cf.json", new Response(200, "   "));

        assertThat(source(1024 * 1024, vendor("cloudflare", "/cf.json", Format.STATUSPAGE)).poll()).isEmpty();
    }

    @Test
    void pollsNothingWhenNoVendorsAreConfigured() throws Exception {
        assertThat(source(1024 * 1024).poll()).isEmpty();
        assertThat(requestedPaths).isEmpty();
    }

    @Test
    void recoversTheVendorKeyFromAnEventsExternalId() {
        assertThat(VendorStatusSource.vendorKeyOf("cloudflare@abc123")).isEqualTo("cloudflare");
        assertThat(VendorStatusSource.vendorKeyOf("cloudflare")).isEqualTo("cloudflare");
        assertThat(VendorStatusSource.vendorKeyOf(null)).isNull();
    }
}
