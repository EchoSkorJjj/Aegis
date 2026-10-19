package com.aegis.core.actions.assess;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Fetching a fix commit's diff (E11-5): what the host answers, only on 200, no redirect followed, at most 1 MB. */
class HttpDiffSourceTest {

    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok.diff", exchange -> respond(exchange, 200, "diff --git a/x b/x\n"));
        server.createContext("/moved.diff", exchange -> {
            exchange.getResponseHeaders().add("Location", base + "/ok.diff");
            respond(exchange, 302, "");
        });
        server.createContext("/missing.diff", exchange -> respond(exchange, 404, "Not Found"));
        server.createContext("/huge.diff", exchange -> respond(exchange, 200, "x".repeat(HttpDiffSource.MAX_BYTES + 10)));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aDiffIsWhatTheHostAnswersWithOnlyAnOkAndNoRedirectFollowed() {
        HttpDiffSource source = new HttpDiffSource();
        assertThat(source.diff(URI.create(base + "/ok.diff"))).contains("diff --git a/x b/x\n");
        assertThat(source.diff(URI.create(base + "/moved.diff"))).isEmpty();
        assertThat(source.diff(URI.create(base + "/missing.diff"))).isEmpty();
        assertThat(source.diff(URI.create("http://127.0.0.1:1/unreachable.diff"))).isEmpty();
    }

    @Test
    void aDiffLargerThanTheLimitIsCutToIt() {
        assertThat(new HttpDiffSource().diff(URI.create(base + "/huge.diff")))
                .hasValueSatisfying(diff -> assertThat(diff).hasSize(HttpDiffSource.MAX_BYTES));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
