package com.aegis.gateway;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * identity-service's /auth rate limit and audit log key on the first X-Forwarded-For hop, which the frontend's
 * BFF sets to the browser's address. The gateway must pass that header on from the BFF, and only from it: a
 * caller outside the trusted addresses could otherwise claim any address it liked.
 */
@SpringBootTest
class ForwardedForTest {

    private static final AtomicReference<String> RECEIVED = new AtomicReference<>();
    private static final HttpServer IDENTITY = startIdentity();

    @DynamicPropertySource
    static void routeToTheStub(DynamicPropertyRegistry registry) {
        registry.add("gateway.identity-uri", () -> "http://127.0.0.1:" + IDENTITY.getAddress().getPort());
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        RECEIVED.set(null);
    }

    @AfterAll
    static void stopIdentity() {
        IDENTITY.stop(0);
    }

    @Test
    void passesOnTheAddressTheBffForwards() throws Exception {
        mvc.perform(get("/auth/config").header("X-Forwarded-For", "203.0.113.7").with(from("10.0.3.25")))
                .andExpect(status().isOk());

        assertThat(RECEIVED.get()).isEqualTo("203.0.113.7, 10.0.3.25");
    }

    @Test
    void replacesTheAddressAnUntrustedCallerClaims() throws Exception {
        mvc.perform(get("/auth/config").header("X-Forwarded-For", "203.0.113.7").with(from("198.51.100.20")))
                .andExpect(status().isOk());

        assertThat(RECEIVED.get()).isEqualTo("198.51.100.20");
    }

    @Test
    void namesTheCallerWhenNothingIsForwarded() throws Exception {
        mvc.perform(get("/auth/config").with(from("10.0.3.25"))).andExpect(status().isOk());

        assertThat(Objects.toString(RECEIVED.get(), "")).isEqualTo("10.0.3.25");
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static HttpServer startIdentity() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ForwardedForTest::respond);
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        RECEIVED.set(exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
