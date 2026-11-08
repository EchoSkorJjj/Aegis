package com.aegis.core.platform.identity;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.aegis.core.platform.common.ApiExceptions;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * identity-service's internal API against a local stand-in: the service token goes on every call, and each answer
 * maps to what the owner-notification job does next (a refusal stops retrying; a failure retries).
 */
class HttpIdentityInternalApiTest {

    private HttpServer server;
    private final AtomicReference<String> seenToken = new AtomicReference<>();
    private final AtomicReference<String> seenPath = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenToken.set(exchange.getRequestHeaders().getFirst("X-Service-Token"));
            seenPath.set(exchange.getRequestURI().getPath());
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpIdentityInternalApi api() {
        return new HttpIdentityInternalApi("http://127.0.0.1:" + server.getAddress().getPort(), "service-token-fixture",
                new ObjectMapper(), ObservationRegistry.NOOP);
    }

    private IdentityInternalApi.EmailOutcome email() {
        return api().emailOwner(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of());
    }

    @Test
    void listsTheOrganizationsWithTheServiceToken() {
        UUID org = UUID.randomUUID();
        body = "[\"" + org + "\"]";

        assertThat(api().organizationIds()).containsExactly(org);
        assertThat(seenToken.get()).isEqualTo("service-token-fixture");
        assertThat(seenPath.get()).isEqualTo("/internal/organizations");
    }

    @Test
    void anEmptyBodyIsNoOrganizations() {
        assertThat(api().organizationIds()).isEmpty();
    }

    @Test
    void aRefusalOrUnreadableListIsAnUpstreamFailureNotNoOrganizations() {
        status = 401;
        assertThatThrownBy(() -> api().organizationIds()).isInstanceOf(ApiExceptions.UpstreamUnavailable.class);

        status = 200;
        body = "not json";
        assertThatThrownBy(() -> api().organizationIds()).isInstanceOf(ApiExceptions.UpstreamUnavailable.class);
    }

    @Test
    void anAcceptedEmailIsSentAndCarriesItsDeliveryId() {
        status = 204;
        UUID delivery = UUID.randomUUID();

        assertThat(api().emailOwner(UUID.randomUUID(), UUID.randomUUID(), delivery, List.of()))
                .isEqualTo(IdentityInternalApi.EmailOutcome.SENT);
        assertThat(seenPath.get()).isEqualTo("/internal/notifications/owner-email");
        assertThat(seenToken.get()).isEqualTo("service-token-fixture");
        assertThat(seenBody.get()).contains(delivery.toString());
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 409})
    void aMemberWhoCantBeReachedIsNotRetried(int refused) {
        status = refused;

        assertThat(email()).isEqualTo(IdentityInternalApi.EmailOutcome.UNREACHABLE);
    }

    @Test
    void aRejectedEmailIsNotRetried() {
        status = 400;

        assertThat(email()).isEqualTo(IdentityInternalApi.EmailOutcome.REJECTED);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 500, 503})
    void anyOtherAnswerIsAFailureToRetry(int failed) {
        status = failed;

        assertThatThrownBy(this::email).isInstanceOf(ApiExceptions.UpstreamUnavailable.class);
    }
}
