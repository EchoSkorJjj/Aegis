package com.aegis.core.platform.identity;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.aegis.core.platform.common.ApiExceptions;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The HTTP member lookup against a local stand-in for identity-service — never the real one. What
 * matters: the <em>caller's</em> token is relayed (so identity-service applies the caller's org scope),
 * a 404 is "not a member", and anything else is an upstream failure rather than a silent pass.
 */
class HttpIdentityDirectoryTest {

    private HttpServer server;
    private final AtomicReference<String> seenAuthorization = new AtomicReference<>();
    private final AtomicReference<String> seenPath = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenPath.set(exchange.getRequestURI().getPath());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("caller-token").header("alg", "none").subject(UUID.randomUUID().toString()).build()));
    }

    @AfterEach
    void stop() {
        SecurityContextHolder.clearContext();
        server.stop(0);
    }

    private HttpIdentityDirectory directory() {
        return new HttpIdentityDirectory(
                "http://127.0.0.1:" + server.getAddress().getPort(), new ObjectMapper(), ObservationRegistry.NOOP);
    }

    @Test
    void relaysTheCallersTokenAndReadsTheMemberIgnoringUnknownFields() {
        UUID id = UUID.randomUUID();
        body = """
                {"id":"%s","email":"jane@example.com","displayName":"Jane","roles":["USER","SERVICE_OWNER"],
                 "enabled":true,"emailVerified":true,"orgId":"%s","createdAt":"2026-09-01T00:00:00Z"}
                """.formatted(id, UUID.randomUUID());

        Optional<DirectoryUser> member = directory().findMember(id);

        assertThat(seenAuthorization.get()).isEqualTo("Bearer caller-token");
        assertThat(seenPath.get()).isEqualTo("/users/" + id);
        assertThat(member).hasValueSatisfying(found -> {
            assertThat(found.id()).isEqualTo(id);
            assertThat(found.email()).isEqualTo("jane@example.com");
            assertThat(found.hasRole("SERVICE_OWNER")).isTrue();
            assertThat(found.enabled()).isTrue();
            assertThat(found.label()).isEqualTo("Jane");
        });
    }

    @Test
    void aNotFoundMeansNotAMemberOfTheCallersOrganization() {
        status = 404;
        body = "{\"status\":404}";

        assertThat(directory().findMember(UUID.randomUUID())).isEmpty();
    }

    @Test
    void anyOtherFailureIsAnUpstreamErrorNotASilentPass() {
        status = 500;
        body = "{\"status\":500}";

        assertThatThrownBy(() -> directory().findMember(UUID.randomUUID()))
                .isInstanceOf(ApiExceptions.UpstreamUnavailable.class);

        status = 200;
        body = "not json";
        assertThatThrownBy(() -> directory().findMember(UUID.randomUUID()))
                .isInstanceOf(ApiExceptions.UpstreamUnavailable.class);
    }

    @Test
    void anUnreachableIdentityServiceIsAnUpstreamError() {
        HttpIdentityDirectory directory = directory();
        server.stop(0);

        assertThatThrownBy(() -> directory.findMember(UUID.randomUUID()))
                .isInstanceOf(ApiExceptions.UpstreamUnavailable.class);
    }

    @Test
    void refusesToLookAnyoneUpWithoutACallerToken() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> directory().findMember(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }
}
