package com.aegis.core.ingestion.news;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.aegis.core.ingestion.RawEvent;
import com.aegis.core.ingestion.news.NewsProperties.Feed;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the connector against a real local HTTP server, so the fetch, the content-addressed
 * de-duplication id, and the per-feed failure isolation are all genuinely exercised.
 */
class NewsSourceTest {

    private static final String FEED = "<rss version=\"2.0\"><channel><item><title>A</title></item></channel></rss>";

    private HttpServer server;
    private final Map<String, Response> routes = new HashMap<>();
    private final List<String> requestedPaths = new ArrayList<>();

    private record Response(int status, String body) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requestedPaths.add(exchange.getRequestURI().getPath());
            Response response = routes.getOrDefault(exchange.getRequestURI().getPath(), new Response(404, "nope"));
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

    private Feed feed(String key, String path) {
        return new Feed(key, key, "http://localhost:" + server.getAddress().getPort() + path);
    }

    private NewsSource source(int maxPayloadBytes, Feed... feeds) {
        return new NewsSource(
                new NewsProperties(true, List.of(feeds), Duration.ofSeconds(2), Duration.ofSeconds(5),
                        maxPayloadBytes, 50, 8L * 1024 * 1024, 5, true),
                ObservationRegistry.NOOP);
    }

    @Test
    void capturesEachFeedDocumentVerbatim() {
        routes.put("/hn.rss", new Response(200, FEED));
        routes.put("/krebs.rss", new Response(200, FEED + "<!-- krebs -->"));

        List<RawEvent> events = source(1024 * 1024, feed("hn", "/hn.rss"), feed("krebs", "/krebs.rss")).poll();

        assertThat(events).hasSize(2);
        assertThat(events.get(0).payload()).isEqualTo(FEED);
        assertThat(events.get(0).contentType()).isEqualTo("application/rss+xml");
    }

    @Test
    void keysEventsByFeedAndContentSoAnUnchangedFeedDeduplicates() {
        routes.put("/hn.rss", new Response(200, FEED));
        NewsSource source = source(1024 * 1024, feed("hn", "/hn.rss"));

        String first = source.poll().get(0).externalId();

        assertThat(first).startsWith("hn@");
        assertThat(source.poll().get(0).externalId()).isEqualTo(first);

        routes.put("/hn.rss", new Response(200, FEED + "<!-- new article -->"));
        assertThat(source.poll().get(0).externalId()).isNotEqualTo(first);
    }

    @Test
    void oneFailingPublisherDoesNotCostTheOthersTheirPoll() {
        routes.put("/broken.rss", new Response(500, "upstream exploded"));
        routes.put("/hn.rss", new Response(200, FEED));

        List<RawEvent> events = source(1024 * 1024,
                feed("broken", "/broken.rss"), feed("hn", "/hn.rss")).poll();

        assertThat(events).singleElement()
                .satisfies(event -> assertThat(event.externalId()).startsWith("hn@"));
        assertThat(requestedPaths).contains("/broken.rss", "/hn.rss");
    }

    @Test
    void skipsADocumentLargerThanTheConfiguredCap() {
        routes.put("/hn.rss", new Response(200, FEED));

        assertThat(source(8, feed("hn", "/hn.rss")).poll()).isEmpty();
    }

    @Test
    void skipsAnEmptyDocument() {
        routes.put("/hn.rss", new Response(200, "   "));

        assertThat(source(1024 * 1024, feed("hn", "/hn.rss")).poll()).isEmpty();
    }

    @Test
    void pollsNothingWhenNoFeedsAreConfigured() {
        assertThat(source(1024 * 1024).poll()).isEmpty();
        assertThat(requestedPaths).isEmpty();
    }

    @Test
    void recoversTheFeedKeyFromAnEventsExternalId() {
        assertThat(NewsSource.feedKeyOf("thehackernews@abc123")).isEqualTo("thehackernews");
        assertThat(NewsSource.feedKeyOf("thehackernews")).isEqualTo("thehackernews");
        assertThat(NewsSource.feedKeyOf(null)).isNull();
    }
}
