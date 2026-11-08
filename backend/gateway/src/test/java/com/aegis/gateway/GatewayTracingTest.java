package com.aegis.gateway;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Exercises the actual servlet filter and gateway HTTP forwarding, without Docker or a JWT issuer. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.tracing.export.zipkin.enabled=false")
class GatewayTracingTest {
    private static final String TRACE = "1234567890abcdef1234567890abcdef";
    private static final String PARENT = "1234567890abcdef";
    private static final AtomicReference<String> RECEIVED = new AtomicReference<>();
    private static final HttpServer CORE = startCore();

    @Value("${local.server.port}")
    private int port;

    @MockitoBean
    private JwtDecoder decoder;

    @DynamicPropertySource
    static void routeToStub(DynamicPropertyRegistry registry) {
        registry.add("gateway.core-uri", () -> "http://127.0.0.1:" + CORE.getAddress().getPort());
    }

    @AfterAll
    static void stopCore() {
        CORE.stop(0);
    }

    @Test
    void forwardsAChildOfTheIncomingTraceToCore() throws Exception {
        when(decoder.decode("test-token")).thenReturn(Jwt.withTokenValue("test-token")
                .header("alg", "RS256").subject("trace-test").claim("roles", List.of("USER")).build());
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/ping"))
                    .header("Authorization", "Bearer test-token")
                    .header("traceparent", "00-" + TRACE + "-" + PARENT + "-01").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
        }
        assertThat(RECEIVED.get()).matches("00-" + TRACE + "-[0-9a-f]{16}-01");
        assertThat(RECEIVED.get().split("-")[2]).isNotEqualTo(PARENT);
    }

    private static HttpServer startCore() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/ping", exchange -> {
                RECEIVED.set(exchange.getRequestHeaders().getFirst("traceparent"));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
