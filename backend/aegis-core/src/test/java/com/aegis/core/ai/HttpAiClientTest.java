package com.aegis.core.ai;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.aegis.core.platform.common.ApiExceptions;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The AI port against a local stand-in for the ai-service: its snake_case contract and safe failures. */
class HttpAiClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() { };

    private HttpServer server;
    private HttpAiClient client;
    private final AtomicReference<String> seenPath = new AtomicReference<>();
    private final AtomicReference<String> seenToken = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenPath.set(exchange.getRequestURI().getPath());
            seenToken.set(exchange.getRequestHeaders().getFirst("X-Service-Token"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
        client = new HttpAiClient(new AiClientConfig().aiRestClient(
                ObservationRegistry.NOOP, "http://127.0.0.1:" + server.getAddress().getPort(), "service-token"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void draftsInTheServicesSnakeCaseContract() {
        body = "{\"draft_type\":\"upgrade\",\"content\":\"Upgrade it.\",\"citations\":[\"C1 text\"],\"added_later\":1}";

        AiDtos.Draft draft = client.draft(new AiDtos.DraftRequest("proposal-1", "upgrade", "context"));

        assertThat(draft).isEqualTo(new AiDtos.Draft("upgrade", "Upgrade it.", List.of("C1 text")));
        assertThat(seenPath.get()).isEqualTo("/v1/draft");
        assertThat(seenToken.get()).isEqualTo("service-token");
        Map<String, Object> sent = JSON.readValue(seenBody.get(), JSON_OBJECT);
        assertThat(sent.keySet()).containsExactlyInAnyOrder("finding_id", "draft_type", "context");
        assertThat(sent.get("finding_id")).isEqualTo("proposal-1");
    }

    @Test
    void assessesInTheServicesSnakeCaseContract() {
        body = "{\"material\":true,\"rationale\":\"Why.\",\"suggested_severity\":\"HIGH\",\"citations\":[]}";

        AiDtos.Assessment assessment = client.assess(new AiDtos.AssessmentRequest("f-1", "advisory", "service"));

        assertThat(assessment.suggestedSeverity()).isEqualTo("HIGH");
        assertThat(JSON.readValue(seenBody.get(), JSON_OBJECT).keySet())
                .containsExactlyInAnyOrder("finding_id", "advisory_text", "service_context");
    }

    @Test
    void draftRequestsAndAllReturnedNarrativeFieldsAreRedactedAtThePort() {
        String sensitive = "alice@example.test password=opaque-credential";
        body = JSON.writeValueAsString(Map.of("draft_type", "risk_ticket", "content", sensitive,
                "ticket", Map.of("summary", sensitive, "risk", sensitive, "impact", sensitive,
                        "actions", List.of(sensitive)), "citations", List.of(sensitive)));

        AiDtos.Draft draft = client.draft(new AiDtos.DraftRequest("proposal-1", "risk_ticket", sensitive));

        assertThat(seenBody.get()).contains("[REDACTED]").doesNotContain("alice@example.test", "opaque-credential");
        assertThat(draft.content()).isEqualTo("[REDACTED] [REDACTED]");
        assertThat(draft.ticket().summary()).isEqualTo(draft.content());
        assertThat(draft.ticket().risk()).isEqualTo(draft.content());
        assertThat(draft.ticket().impact()).isEqualTo(draft.content());
        assertThat(draft.ticket().actions()).containsExactly(draft.content());
        assertThat(draft.citations()).containsExactly(draft.content());
    }

    @Test
    void assessmentAndMemoFieldsAreProtectedEvenIfTheAiServiceReturnsSensitiveText() {
        body = "{\"material\":true,\"rationale\":\"alice@example.test\",\"suggested_severity\":\"HIGH\","
                + "\"citations\":[\"password=opaque-credential\"]}";
        AiDtos.Assessment result = client.assess(new AiDtos.AssessmentRequest("f-1", "password=opaque-credential",
                "alice@example.test"));
        assertThat(seenBody.get()).doesNotContain("alice@example.test", "opaque-credential");
        assertThat(result.rationale()).isEqualTo("[REDACTED]");
        assertThat(result.citations()).containsExactly("[REDACTED]");
        var memo = new AiDtos.MemoSections("alice@example.test", "phone: 555-123-4567",
                "password=opaque-credential", "full_name: Alice Smith");
        assertThat(List.of(memo.summary(), memo.impact(), memo.measures(), memo.nextSteps()))
                .containsOnly("[REDACTED]");
    }

    @Test
    void anErrorStatusBecomesASafeUpstreamFailure() {
        status = 503;
        body = "{\"message\":\"LLM daily token budget exhausted\"}";

        assertThatThrownBy(() -> client.draft(new AiDtos.DraftRequest("p", "upgrade", null)))
                .isInstanceOf(ApiExceptions.UpstreamUnavailable.class)
                .hasMessage("The AI service could not complete the request (HTTP 503)");
    }

    @Test
    void anUnreachableServiceBecomesASafeUpstreamFailure() {
        server.stop(0);

        assertThatThrownBy(() -> client.draft(new AiDtos.DraftRequest("p", "upgrade", null)))
                .isInstanceOf(ApiExceptions.UpstreamUnavailable.class)
                .hasMessageContaining("unreachable");
    }
}
