package com.aegis.core.actions.tracker.jira;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import com.aegis.core.actions.tracker.IssueTracker.Block;
import com.aegis.core.actions.tracker.IssueTracker.FiledIssue;
import com.aegis.core.actions.tracker.IssueTracker.IssueDraft;
import com.aegis.core.actions.tracker.IssueTracker.Priority;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.crypto.SecretBox;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Connect Jira and filing risk tickets against a fake Atlassian: the consent round trip is bound to the
 * admin who started it, tokens are only ever stored sealed and renewed as they rotate, and an approved
 * ticket becomes a Jira issue of plain text — with a site's missing priority or due date no reason to
 * refuse it. No token ever surfaces in an error.
 */
class JiraAdapterTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String CLOUD_ID = "11111111-2222-3333-4444-555555555555";
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();

    private final MutableClock clock = new MutableClock();
    private final FakeStore store = new FakeStore();
    private final SecretBox box = SecretBox.fromBase64(key());
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Deque<Response>> responses = new HashMap<>();
    private HttpServer server;
    private JiraConnections connections;
    private JiraIssueTracker tracker;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        var properties = new JiraProperties(true, "jira-client", "jira-client-secret",
                URI.create("http://localhost:3000/admin/integrations/jira/callback"), URI.create(origin + "/auth"),
                URI.create(origin + "/api"));
        var api = new JiraApi(properties, MAPPER, ObservationRegistry.NOOP);
        connections = new JiraConnections(properties, api, store, box, () -> ORG, clock);
        tracker = new JiraIssueTracker(api, connections);

        respond("POST /auth/oauth/token", 200, tokens("access-1", "refresh-1"));
        respond("GET /api/oauth/token/accessible-resources", 200, """
                [{"id":"99999999-0000-0000-0000-000000000000","name":"Read only","url":"https://read.atlassian.net",
                  "scopes":["read:jira-work"]},
                 {"id":"%s","name":"Acme","url":"https://acme.atlassian.net","scopes":["read:jira-work","write:jira-work"]}]
                """.formatted(CLOUD_ID));
        respond("GET /api/ex/jira/" + CLOUD_ID + "/rest/api/3/project/search", 200,
                "{\"values\":[{\"id\":\"1\",\"key\":\"SEC\",\"name\":\"Security\"}]}");
        respond("GET /api/ex/jira/" + CLOUD_ID + "/rest/api/3/project/SEC", 200,
                "{\"key\":\"SEC\",\"issueTypes\":[{\"id\":\"1\",\"name\":\"Task\",\"subtask\":false},"
                        + "{\"id\":\"2\",\"name\":\"Sub-task\",\"subtask\":true}]}");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void anAdminGrantsASiteAndTheTokensAreOnlyEverStoredSealed() {
        URI authorize = connections.start(ADMIN);
        Map<String, String> query = query(authorize);
        assertThat(authorize.getPath()).isEqualTo("/auth/authorize");
        assertThat(query).containsEntry("audience", "api.atlassian.com").containsEntry("client_id", "jira-client")
                .containsEntry("scope", "read:jira-work write:jira-work offline_access")
                .containsEntry("redirect_uri", "http://localhost:3000/admin/integrations/jira/callback")
                .containsEntry("response_type", "code").containsEntry("prompt", "consent");

        var connection = connections.complete(ADMIN, "the-code", query.get("state"));

        assertThat(connection.cloudId()).as("the first site Aegis may create issues in").isEqualTo(CLOUD_ID);
        assertThat(connection.siteUrl()).isEqualTo("https://acme.atlassian.net");
        assertThat(connection.configured()).isFalse();
        JsonNode exchange = json(request("POST /auth/oauth/token").body());
        assertThat(exchange.path("grant_type").asString()).isEqualTo("authorization_code");
        assertThat(exchange.path("client_secret").asString()).isEqualTo("jira-client-secret");
        assertThat(exchange.path("code").asString()).isEqualTo("the-code");
        assertThat(exchange.path("redirect_uri").asString()).isEqualTo("http://localhost:3000/admin/integrations/jira/callback");
        assertThat(store.tokens.accessToken()).doesNotContain("access-1");
        assertThat(store.tokens.refreshToken()).doesNotContain("refresh-1");
        assertThat(box.open(store.tokens.refreshToken(), "jira:" + ORG)).isEqualTo("refresh-1");
    }

    @Test
    void aSignInCompletesOnceOnlyForWhoeverStartedItAndOnlyInTime() {
        assertThatThrownBy(() -> connections.complete(ADMIN, "the-code", "never-issued"))
                .isInstanceOf(ApiExceptions.Forbidden.class);
        String someoneElses = query(connections.start(ADMIN)).get("state");
        assertThatThrownBy(() -> connections.complete(UUID.randomUUID(), "the-code", someoneElses))
                .isInstanceOf(ApiExceptions.Forbidden.class);
        assertThatThrownBy(() -> connections.complete(ADMIN, "the-code", someoneElses))
                .isInstanceOf(ApiExceptions.Forbidden.class);
        String late = query(connections.start(ADMIN)).get("state");
        clock.now = clock.now.plus(JiraConnections.PENDING_FOR).plusSeconds(1);
        assertThatThrownBy(() -> connections.complete(ADMIN, "the-code", late)).isInstanceOf(ApiExceptions.Forbidden.class);
        assertThat(requests).as("Atlassian is never asked for a token on a bad state").isEmpty();
    }

    @Test
    void aRefusedSignInConnectsNothing() {
        respond("POST /auth/oauth/token", 400, "{\"error\":\"invalid_grant\"}");
        assertThatThrownBy(() -> connections.complete(ADMIN, "stale", query(connections.start(ADMIN)).get("state")))
                .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("connect Jira again");
        assertThat(store.connection).isNull();
    }

    @Test
    void theAdminChoosesAProjectAndAnIssueTypeItReallyHas() {
        connect();
        var options = connections.options(null, "SEC");
        assertThat(options.sites()).extracting(JiraConnections.Site::name).containsExactly("Acme");
        assertThat(options.projects()).extracting(JiraApi.Project::key).containsExactly("SEC");
        assertThat(options.issueTypes()).as("sub-tasks can't be filed on their own").containsExactly("Task");

        assertThatThrownBy(() -> connections.configure(CLOUD_ID, "SEC", "Epic")).isInstanceOf(ApiExceptions.BadRequest.class);
        assertThatThrownBy(() -> connections.configure("99999999-0000-0000-0000-000000000000", "SEC", "Task"))
                .as("a read-only site").isInstanceOf(ApiExceptions.BadRequest.class);
        assertThatThrownBy(() -> connections.configure(CLOUD_ID, "sec; drop", "Task")).isInstanceOf(ApiExceptions.BadRequest.class);
        assertThat(connections.configure(CLOUD_ID, "SEC", "Task").configured()).isTrue();
    }

    @Test
    void anExpiringAccessTokenIsRenewedAndTheRotatedRefreshTokenKept() {
        connect();
        requests.clear();
        assertThat(connections.accessToken()).isEqualTo("access-1");
        assertThat(requests).isEmpty();

        respond("POST /auth/oauth/token", 200, tokens("access-2", "refresh-2"));
        clock.now = clock.now.plus(Duration.ofMinutes(59));
        assertThat(connections.accessToken()).isEqualTo("access-2");
        JsonNode renewal = json(request("POST /auth/oauth/token").body());
        assertThat(renewal.path("grant_type").asString()).isEqualTo("refresh_token");
        assertThat(renewal.path("refresh_token").asString()).isEqualTo("refresh-1");
        assertThat(box.open(store.tokens.refreshToken(), "jira:" + ORG)).isEqualTo("refresh-2");
    }

    @Test
    void anApprovedTicketBecomesAJiraIssueOfPlainText() {
        configure();
        respond("POST /api/ex/jira/" + CLOUD_ID + "/rest/api/3/issue", 201, "{\"id\":\"10001\",\"key\":\"SEC-42\"}");

        FiledIssue filed = tracker.file(issue());

        assertThat(filed).isEqualTo(new FiledIssue("SEC-42", "https://acme.atlassian.net/browse/SEC-42"));
        Request created = request("POST /api/ex/jira/" + CLOUD_ID + "/rest/api/3/issue");
        assertThat(created.authorization()).isEqualTo("Bearer access-1");
        JsonNode fields = json(created.body()).path("fields");
        assertThat(fields.path("project").path("key").asString()).isEqualTo("SEC");
        assertThat(fields.path("issuetype").path("name").asString()).isEqualTo("Task");
        assertThat(fields.path("summary").asString()).isEqualTo("CVE-2021-44228 affects Payments API");
        assertThat(fields.path("priority").path("name").asString()).isEqualTo("Highest");
        assertThat(fields.path("duedate").asString()).isEqualTo("2026-10-03");
        assertThat(fields.path("labels").get(1).asString()).isEqualTo("CVE-2021-44228");
        JsonNode description = fields.path("description");
        assertThat(description.path("type").asString()).isEqualTo("doc");
        assertThat(description.path("content").get(0).path("content").get(0).path("text").asString())
                .isEqualTo("<b>markup</b> stays text");
        assertThat(description.path("content").get(1).path("type").asString()).isEqualTo("heading");
        assertThat(description.path("content").get(2).path("content").get(0).path("content").get(0).path("content")
                .get(0).path("text").asString()).isEqualTo("Upgrade log4j-core.");
    }

    @Test
    void aSiteWithoutPriorityOrDueDateStillGetsTheTicketWithoutThem() {
        configure();
        respondInOrder("POST /api/ex/jira/" + CLOUD_ID + "/rest/api/3/issue",
                new Response(400, "{\"errors\":{\"priority\":\"Field cannot be set\",\"duedate\":\"Field cannot be set\"}}"),
                new Response(201, "{\"id\":\"10002\",\"key\":\"SEC-43\"}"));

        assertThat(tracker.file(issue()).key()).isEqualTo("SEC-43");
        List<Request> creates = requests("POST /api/ex/jira/" + CLOUD_ID + "/rest/api/3/issue");
        assertThat(creates).hasSize(2);
        assertThat(json(creates.get(1).body()).path("fields").has("priority")).isFalse();
        assertThat(json(creates.get(1).body()).path("fields").has("duedate")).isFalse();
    }

    @Test
    void aRequiredFieldJiraRejectsIsAConflictNotARetry() {
        configure();
        respond("POST /api/ex/jira/" + CLOUD_ID + "/rest/api/3/issue", 400,
                "{\"errors\":{\"components\":\"Component is required\"}}");
        assertThatThrownBy(() -> tracker.file(issue())).isInstanceOf(ApiExceptions.Conflict.class)
                .hasMessageContaining("components").hasMessageNotContaining("Component is required");
        assertThat(requests("POST /api/ex/jira/" + CLOUD_ID + "/rest/api/3/issue")).hasSize(1);
    }

    @Test
    void aTokenJiraRejectsEarlyIsRenewedOnce() {
        configure();
        respond("POST /auth/oauth/token", 200, tokens("access-2", "refresh-2"));
        respondInOrder("POST /api/ex/jira/" + CLOUD_ID + "/rest/api/3/issue",
                new Response(401, "{}"), new Response(201, "{\"id\":\"10003\",\"key\":\"SEC-44\"}"));
        assertThat(tracker.file(issue()).key()).isEqualTo("SEC-44");
        assertThat(requests("POST /api/ex/jira/" + CLOUD_ID + "/rest/api/3/issue").get(1).authorization())
                .isEqualTo("Bearer access-2");

        respondInOrder("POST /api/ex/jira/" + CLOUD_ID + "/rest/api/3/issue", new Response(401, "{}"), new Response(401, "{}"));
        assertThatThrownBy(() -> tracker.file(issue())).isInstanceOf(ApiExceptions.UpstreamUnavailable.class)
                .hasMessageContaining("connect Jira again");
    }

    @Test
    void nothingIsFiledUntilJiraIsConnectedAndAProjectChosen() {
        assertThatThrownBy(() -> tracker.file(issue())).isInstanceOf(ApiExceptions.Conflict.class)
                .hasMessageContaining("Jira isn't connected");
        connect();
        assertThatThrownBy(() -> tracker.file(issue())).isInstanceOf(ApiExceptions.Conflict.class)
                .hasMessageContaining("Choose the Jira project");
    }

    @Test
    void failuresNeverCarryAToken() {
        respond("POST /auth/oauth/token", 200, "{\"access_token\":\"access-leak\" not json");
        assertThatThrownBy(() -> connections.complete(ADMIN, "the-code", query(connections.start(ADMIN)).get("state")))
                .isInstanceOf(ApiExceptions.UpstreamUnavailable.class)
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("access-leak"));
    }

    // ---- fake Atlassian and store -----------------------------------------------------------------

    private void connect() {
        connections.complete(ADMIN, "the-code", query(connections.start(ADMIN)).get("state"));
    }

    private void configure() {
        connect();
        connections.configure(CLOUD_ID, "SEC", "Task");
        requests.clear();
    }

    private static IssueDraft issue() {
        return new IssueDraft("CVE-2021-44228 affects Payments API",
                List.of(Block.paragraph("<b>markup</b> stays text"), Block.heading("Recommended actions"),
                        Block.bullets(List.of("Upgrade log4j-core.", " "))),
                Priority.HIGHEST, LocalDate.parse("2026-10-03"), List.of("aegis", "CVE-2021-44228"));
    }

    private static String tokens(String access, String refresh) {
        return "{\"access_token\":\"" + access + "\",\"refresh_token\":\"" + refresh
                + "\",\"expires_in\":3600,\"scope\":\"read:jira-work write:jira-work offline_access\"}";
    }

    private void respond(String route, int status, String body) {
        Deque<Response> queue = new ArrayDeque<>();
        queue.add(new Response(status, body));
        responses.put(route, queue);
    }

    /** Answer in this order; the last answer repeats. */
    private void respondInOrder(String route, Response... answers) {
        responses.put(route, new ArrayDeque<>(List.of(answers)));
    }

    private Request request(String route) {
        return requests(route).getLast();
    }

    private List<Request> requests(String route) {
        synchronized (requests) {
            return requests.stream().filter(request -> request.route().equals(route)).toList();
        }
    }

    private synchronized void respond(HttpExchange exchange) throws IOException {
        String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        requests.add(new Request(route, exchange.getRequestHeaders().getFirst("Authorization"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        Deque<Response> queue = responses.get(route);
        Response response = queue == null ? new Response(404, "{}") : queue.size() > 1 ? queue.poll() : queue.peek();
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static JsonNode json(String body) {
        return MAPPER.readTree(body);
    }

    private static Map<String, String> query(URI uri) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().toSingleValueMap().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> URLDecoder.decode(entry.getValue(), StandardCharsets.UTF_8)));
    }

    private static String key() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private record Request(String route, String authorization, String body) {
    }

    private record Response(int status, String body) {
    }

    /** One organization's rows, as the store scopes them to the caller's organization. */
    private final class FakeStore implements JiraConnectionStore {
        Connection connection;
        SealedTokens tokens;
        final Map<String, PendingConnection> pending = new HashMap<>();

        @Override
        public Optional<Connection> find() {
            return Optional.ofNullable(connection);
        }

        @Override
        public Connection connect(String cloudId, String siteName, String siteUrl, SealedTokens tokens, UUID connectedBy) {
            this.tokens = tokens;
            connection = new Connection(cloudId, siteName, siteUrl, null, null, connectedBy, clock.instant());
            return connection;
        }

        @Override
        public Connection configure(String cloudId, String siteName, String siteUrl, String projectKey, String issueType) {
            connection = new Connection(cloudId, siteName, siteUrl, projectKey, issueType, connection.connectedBy(),
                    connection.connectedAt());
            return connection;
        }

        @Override
        public boolean disconnect() {
            boolean had = connection != null;
            connection = null;
            tokens = null;
            return had;
        }

        @Override
        public Optional<SealedTokens> tokens() {
            return Optional.ofNullable(tokens);
        }

        @Override
        public SealedTokens renewTokens(UnaryOperator<SealedTokens> renew) {
            if (tokens == null) {
                throw JiraConnections.notConnected();
            }
            tokens = renew.apply(tokens);
            return tokens;
        }

        @Override
        public void startConnecting(String stateHash, UUID userId, Instant expiresAt, Instant now) {
            pending.put(stateHash, new PendingConnection(userId, expiresAt));
        }

        @Override
        public Optional<PendingConnection> takePending(String stateHash) {
            return Optional.ofNullable(pending.remove(stateHash));
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-26T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
