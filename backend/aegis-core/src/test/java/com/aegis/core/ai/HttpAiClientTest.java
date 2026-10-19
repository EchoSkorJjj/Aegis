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
    /** How many requests to refuse first with 429 and {@link #retryAfter}. */
    private final java.util.concurrent.atomic.AtomicInteger refusals = new java.util.concurrent.atomic.AtomicInteger();
    private volatile String retryAfter;
    private final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
    private final List<java.time.Duration> slept = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenPath.set(exchange.getRequestURI().getPath());
            seenToken.set(exchange.getRequestHeaders().getFirst("X-Service-Token"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.incrementAndGet();
            if (refusals.getAndUpdate(left -> Math.max(left - 1, 0)) > 0) {
                if (retryAfter != null) {
                    exchange.getResponseHeaders().set("Retry-After", retryAfter);
                }
                exchange.sendResponseHeaders(429, -1);
                exchange.close();
                return;
            }
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
                ObservationRegistry.NOOP, "http://127.0.0.1:" + server.getAddress().getPort(), "service-token"),
                slept::add);
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
    void asksWhatToSearchForInTheServicesSnakeCaseContract() {
        body = "{\"terms\":[\"template\"],\"added_later\":1}";

        AiDtos.LookFor lookFor = client.lookFor(new AiDtos.LookForRequest("f-1", "advisory",
                new AiDtos.PackageRef("npm", "lodash"), "lodash.js: lines changed: template()"));

        assertThat(lookFor.terms()).containsExactly("template");
        assertThat(seenPath.get()).isEqualTo("/v1/assess/look-for");
        Map<String, Object> sent = JSON.readValue(seenBody.get(), JSON_OBJECT);
        assertThat(sent).containsOnlyKeys("finding_id", "advisory_text", "package", "fix_summary")
                .containsEntry("fix_summary", "lodash.js: lines changed: template()");
    }

    @Test
    void assessesReachabilityInTheServicesSnakeCaseContract() {
        body = "{\"verdict\":\"NOT_CALLED\",\"rationale\":\"Why.\",\"checks\":[\"Check it.\"],"
                + "\"cited_excerpts\":[\"E1\"],\"added_later\":1}";

        AiDtos.Assessment assessment = client.assess(new AiDtos.AssessmentRequest("f-1", "advisory",
                new AiDtos.PackageRef("npm", "lodash"), null,
                List.of(new AiDtos.Search("template", 40, 0, 0, true)),
                List.of(new AiDtos.CodeExcerpt("E1", "src/app.ts", 4, "merge(a, b);", "use", null))));

        assertThat(assessment).isEqualTo(new AiDtos.Assessment("NOT_CALLED", "Why.", List.of("Check it."), List.of("E1")));
        assertThat(seenPath.get()).isEqualTo("/v1/assess");
        Map<String, Object> sent = JSON.readValue(seenBody.get(), JSON_OBJECT);
        assertThat(sent.keySet()).containsExactlyInAnyOrder("finding_id", "advisory_text", "package", "fix_summary",
                "searches", "excerpts");
        assertThat(sent.get("package")).isEqualTo(Map.of("ecosystem", "npm", "name", "lodash"));
        assertThat(sent.get("searches")).isEqualTo(List.of(Map.of("term", "template", "files_searched", 40,
                "files_matched", 0, "lines_matched", 0, "complete", true)));
        Map<String, Object> excerpt = new java.util.HashMap<>(Map.of("id", "E1", "path", "src/app.ts", "start_line", 4,
                "text", "merge(a, b);", "kind", "use"));
        excerpt.put("term", null);
        assertThat(sent.get("excerpts")).isEqualTo(List.of(excerpt));
    }

    @Test
    void aFullMinuteIsWaitedOutOnceThenTheCallIsMadeAgain() {
        // An assessment is two calls on one per-minute budget: the second may find it full for a few seconds.
        body = "{\"terms\":[\"template\"]}";
        refusals.set(1);
        retryAfter = "7";

        AiDtos.LookFor lookFor = client.lookFor(new AiDtos.LookForRequest("f-1", "advisory",
                new AiDtos.PackageRef("npm", "lodash"), null));

        assertThat(lookFor.terms()).containsExactly("template");
        assertThat(slept).containsExactly(java.time.Duration.ofSeconds(7));
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void aWaitOfMoreThanAMinuteASecondRefusalOrNoRetryAfterIsTheAiAtItsLimit() {
        for (String after : new String[] {"120", null}) {
            refusals.set(1);
            retryAfter = after;
            assertThatThrownBy(() -> client.lookFor(new AiDtos.LookForRequest("f-1", "a", new AiDtos.PackageRef("npm", "x"),
                    null))).isInstanceOf(ApiExceptions.UpstreamUnavailable.class).hasMessageContaining("(HTTP 429)");
        }
        assertThat(slept).isEmpty();

        refusals.set(2);
        retryAfter = "5";
        assertThatThrownBy(() -> client.lookFor(new AiDtos.LookForRequest("f-1", "a", new AiDtos.PackageRef("npm", "x"),
                null))).isInstanceOf(ApiExceptions.UpstreamUnavailable.class).hasMessageContaining("(HTTP 429)");
        assertThat(slept).containsExactly(java.time.Duration.ofSeconds(5));
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
        body = "{\"verdict\":\"CALLED\",\"rationale\":\"alice@example.test\",\"checks\":[\"alice@example.test\"],"
                + "\"cited_excerpts\":[\"E1\"]}";
        AiDtos.Assessment result = client.assess(new AiDtos.AssessmentRequest("f-1", "password=opaque-credential",
                new AiDtos.PackageRef("npm", "lodash"), "a.js: password=opaque-credential", List.of(),
                List.of(new AiDtos.CodeExcerpt("E1", "src/app.ts", 1,
                        "const db = connect('password=opaque-credential'); // alice@example.test", "use", null))));
        // The code leaves Aegis only after redaction, like every other field.
        assertThat(seenBody.get()).doesNotContain("alice@example.test", "opaque-credential");
        assertThat(result.rationale()).isEqualTo("[REDACTED]");
        assertThat(result.checks()).containsExactly("[REDACTED]");
        client.lookFor(new AiDtos.LookForRequest("f-1", "password=opaque-credential", new AiDtos.PackageRef("npm", "x"),
                "alice@example.test"));
        assertThat(seenBody.get()).doesNotContain("alice@example.test", "opaque-credential");
        assertThat(result.citedExcerpts()).containsExactly("E1");
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
