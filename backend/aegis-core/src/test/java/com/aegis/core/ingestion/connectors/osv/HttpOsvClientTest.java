package com.aegis.core.ingestion.connectors.osv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The bulk download against a local stand-in for OSV's bucket: untrusted archives stay bounded, record by record. */
class HttpOsvClientTest {

    private HttpServer server;
    private final AtomicReference<String> seenPath = new AtomicReference<>();
    private volatile int status = 200;
    private volatile byte[] archive = new byte[0];

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenPath.set(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(status, archive.length == 0 ? -1 : archive.length);
            if (archive.length > 0) {
                exchange.getResponseBody().write(archive);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpOsvClient client(boolean allowPrivateHosts) {
        return new HttpOsvClient(new OsvProperties(true, "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                List.of("npm"), Duration.ofSeconds(2), Duration.ofSeconds(5), 0, 0, 0, allowPrivateHosts, false));
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Map<String, String> entries(String... namesAndBodies) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int index = 0; index < namesAndBodies.length; index += 2) {
            entries.put(namesAndBodies[index], namesAndBodies[index + 1]);
        }
        return entries;
    }

    @Test
    void streamsEachJsonRecordUnderItsIdFromTheEcosystemsExport() throws Exception {
        archive = zip(entries(
                "GHSA-1.json", "{\"id\":\"GHSA-1\"}",
                "nested/GHSA-2.json", "{\"id\":\"GHSA-2\"}",
                "README.txt", "not a record",
                "folder/", ""));
        Map<String, String> seen = new LinkedHashMap<>();

        client(true).forEachRecord("npm", 10, 1024, seen::put);

        assertThat(seenPath.get()).isEqualTo("/npm/all.zip");
        assertThat(seen).containsOnlyKeys("GHSA-1", "GHSA-2");
        assertThat(seen.get("GHSA-2")).isEqualTo("{\"id\":\"GHSA-2\"}");
    }

    @Test
    void skipsARecordOverTheCapOrEmptyAndKeepsGoing() throws Exception {
        archive = zip(entries(
                "huge.json", "x".repeat(100),
                "empty.json", "",
                "fits.json", "{}"));
        Map<String, String> seen = new LinkedHashMap<>();

        client(true).forEachRecord("npm", 10, 10, seen::put);

        assertThat(seen).containsOnlyKeys("fits");
    }

    @Test
    void stopsAtTheRecordLimit() throws Exception {
        archive = zip(entries("a.json", "{}", "b.json", "{}", "c.json", "{}"));
        Map<String, String> seen = new LinkedHashMap<>();

        client(true).forEachRecord("npm", 2, 1024, seen::put);

        assertThat(seen).containsOnlyKeys("a", "b");
    }

    @Test
    void aFailedDownloadIsAnErrorNotAnEmptyFeed() {
        status = 503;

        assertThatThrownBy(() -> client(true).forEachRecord("npm", 10, 1024, (id, json) -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 503");
    }

    @Test
    void refusesAPrivateAddressUnlessWaived() {
        assertThatThrownBy(() -> client(false).forEachRecord("npm", 10, 1024, (id, json) -> { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-public");
        assertThat(seenPath.get()).isNull();
    }
}
