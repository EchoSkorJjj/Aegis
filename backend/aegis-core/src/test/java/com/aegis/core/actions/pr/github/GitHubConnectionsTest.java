package com.aegis.core.actions.pr.github;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.aegis.core.actions.pr.github.GitHubConnectionStore.Connection;
import com.aegis.core.platform.common.ApiExceptions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Connect GitHub against a fake GitHub: an organization admin connects exactly the installations they
 * control — their own account, or a GitHub organization they administer — through a one-time state bound to
 * them, and the user token is revoked and never surfaces in an error.
 */
class GitHubConnectionsTest {

    private static final String CLIENT_ID = "Iv1.test-client";
    private static final String CLIENT_SECRET = "test-client-secret";
    private static final String USER_TOKEN = "ghu_user-token-secret";
    private static final UUID ADMIN = UUID.randomUUID();

    @TempDir Path temporary;

    private final MutableClock clock = new MutableClock();
    private final FakeStore store = new FakeStore();
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Response> responses = new HashMap<>();
    private HttpServer server;
    private GitHubConnections connections;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        var properties = new GitHubProperties(true, URI.create(origin + "/api/v3"), URI.create(origin), CLIENT_ID,
                CLIENT_SECRET, null, null, URI.create("http://localhost:3000/admin/integrations/github/callback"),
                Set.of("github.com"));
        var mapper = JsonMapper.builder().build();
        var api = new GitHubApi(properties.apiBaseUrl(), mapper, ObservationRegistry.NOOP);
        var jwt = new GitHubAppJwt(GitHubAppPrProviderTest.writeKey(temporary, GitHubAppPrProviderTest.generateKey()),
                CLIENT_ID, clock);
        var users = new GitHubUserAuthorization(properties, api, mapper, ObservationRegistry.NOOP);
        connections = new GitHubConnections(properties, api, jwt, users, store, clock);

        respond("POST /login/oauth/access_token", 200, "{\"access_token\":\"" + USER_TOKEN + "\",\"token_type\":\"bearer\"}");
        respond("GET /api/v3/user", 200, "{\"id\":1001,\"login\":\"octo\",\"type\":\"User\"}");
        respond("DELETE /api/v3/applications/" + CLIENT_ID + "/token", 204, "");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void connectsTheInstallationOnTheAdminsOwnAccountAndRevokesTheToken() {
        installations(installation(42, 1001, "octo", "User"));

        URI authorize = connections.start(ADMIN);
        Map<String, String> query = query(authorize);
        assertThat(authorize.getPath()).isEqualTo("/login/oauth/authorize");
        assertThat(query).containsEntry("client_id", CLIENT_ID)
                .containsEntry("redirect_uri", "http://localhost:3000/admin/integrations/github/callback")
                .containsEntry("allow_signup", "false");
        assertThat(store.pending).containsOnlyKeys(GitHubConnections.hash(query.get("state")));

        var completed = connections.complete(ADMIN, "the-code", query.get("state"));

        assertThat(completed.connected()).singleElement().satisfies(connection -> {
            assertThat(connection.installationId()).isEqualTo(42);
            assertThat(connection.accountLogin()).isEqualTo("octo");
            assertThat(connection.accountType()).isEqualTo("User");
            assertThat(connection.connectedBy()).isEqualTo(ADMIN);
        });
        assertThat(completed.skipped()).isEmpty();
        Request exchange = request("POST /login/oauth/access_token");
        assertThat(form(exchange.body())).containsEntry("client_id", CLIENT_ID).containsEntry("client_secret", CLIENT_SECRET)
                .containsEntry("code", "the-code")
                .containsEntry("redirect_uri", "http://localhost:3000/admin/integrations/github/callback");
        assertThat(request("GET /api/v3/user").authorization()).isEqualTo("Bearer " + USER_TOKEN);
        Request revoke = request("DELETE /api/v3/applications/" + CLIENT_ID + "/token");
        assertThat(revoke.authorization()).isEqualTo("Basic " + Base64.getEncoder().encodeToString(
                (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8)));
        assertThat(revoke.body()).contains(USER_TOKEN);
        assertThat(store.pending).as("the state is used up").isEmpty();
    }

    @Test
    void aGitHubOrganizationIsConnectedOnlyByItsAdmins() {
        installations(installation(1, 501, "acme", "Organization"), installation(2, 502, "guild", "Organization"),
                installation(3, 503, "stranger-co", "Organization"), installation(4, 504, "locked", "Organization"));
        respond("GET /api/v3/user/memberships/orgs/acme", 200, "{\"state\":\"active\",\"role\":\"admin\"}");
        respond("GET /api/v3/user/memberships/orgs/guild", 200, "{\"state\":\"active\",\"role\":\"member\"}");
        respond("GET /api/v3/user/memberships/orgs/stranger-co", 404, "{}");
        respond("GET /api/v3/user/memberships/orgs/locked", 403, "{}");

        var completed = connections.complete(ADMIN, "the-code", state());

        assertThat(completed.connected()).extracting(Connection::accountLogin).containsExactly("acme");
        assertThat(completed.skipped()).extracting(GitHubConnections.Skipped::accountLogin)
                .containsExactly("guild", "stranger-co", "locked");
        assertThat(completed.skipped().get(0).reason()).isEqualTo("Only an admin of the guild GitHub organization can connect it");
        assertThat(completed.skipped().get(2).reason()).contains("give the App read access to organization members");
        assertThat(store.connected).containsOnlyKeys(1L);
    }

    @Test
    void anotherPersonsAccountIsNeverConnected() {
        installations(installation(9, 2002, "someone-else", "User"));

        var completed = connections.complete(ADMIN, "the-code", state());

        assertThat(completed.connected()).isEmpty();
        assertThat(completed.skipped()).singleElement()
                .isEqualTo(new GitHubConnections.Skipped("someone-else", "It is installed on another person's GitHub account"));
    }

    @Test
    void aStateCompletesOnceOnlyForWhoeverStartedItAndOnlyInTime() {
        installations(installation(42, 1001, "octo", "User"));
        assertThatThrownBy(() -> connections.complete(ADMIN, "the-code", "never-issued"))
                .isInstanceOf(ApiExceptions.Forbidden.class);

        String someoneElses = state();
        assertThatThrownBy(() -> connections.complete(UUID.randomUUID(), "the-code", someoneElses))
                .isInstanceOf(ApiExceptions.Forbidden.class);
        assertThatThrownBy(() -> connections.complete(ADMIN, "the-code", someoneElses))
                .as("a refused attempt still uses the state up").isInstanceOf(ApiExceptions.Forbidden.class);

        String late = state();
        clock.now = clock.now.plus(GitHubConnections.PENDING_FOR).plusSeconds(1);
        assertThatThrownBy(() -> connections.complete(ADMIN, "the-code", late)).isInstanceOf(ApiExceptions.Forbidden.class);

        assertThat(requests).as("GitHub is never asked for a token on a bad state").isEmpty();
        assertThat(store.connected).isEmpty();
    }

    @Test
    void aCodeGitHubRefusesConnectsNothing() {
        respond("POST /login/oauth/access_token", 200,
                "{\"error\":\"bad_verification_code\",\"error_description\":\"The code passed is incorrect or expired.\"}");

        assertThatThrownBy(() -> connections.complete(ADMIN, "stale-code", state()))
                .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("connect again");
        assertThat(store.connected).isEmpty();
    }

    @Test
    void failuresNeverCarryTheUserToken() {
        respond("POST /login/oauth/access_token", 200, "{\"access_token\":\"" + USER_TOKEN + "\" not json");
        assertThatThrownBy(() -> connections.complete(ADMIN, "the-code", state()))
                .isInstanceOf(ApiExceptions.UpstreamUnavailable.class)
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(USER_TOKEN));

        respond("POST /login/oauth/access_token", 200, "{\"access_token\":\"" + USER_TOKEN + "\"}");
        respond("GET /api/v3/user", 500, "{\"token\":\"" + USER_TOKEN + "\"}");
        assertThatThrownBy(() -> connections.complete(ADMIN, "the-code", state()))
                .isInstanceOf(ApiExceptions.UpstreamUnavailable.class)
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(USER_TOKEN));
        assertThat(requests).as("the token is revoked even when connecting fails")
                .anyMatch(request -> request.route().startsWith("DELETE /api/v3/applications/"));
    }

    @Test
    void theInstallLinkIsTheAppsOwnPageAndIsAskedForOnce() {
        respond("GET /api/v3/app", 200, "{\"slug\":\"aegis-cs203\",\"name\":\"aegis-cs203\"}");

        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        assertThat(connections.installUrl()).isEqualTo(origin + "/apps/aegis-cs203/installations/new");
        assertThat(connections.installUrl()).isEqualTo(origin + "/apps/aegis-cs203/installations/new");
        assertThat(requests).filteredOn(request -> request.route().equals("GET /api/v3/app")).hasSize(1);
        assertThat(request("GET /api/v3/app").authorization()).startsWith("Bearer ey");
    }

    @Test
    void disconnectingWhatIsNotConnectedIsNotFound() {
        store.connected.put(42L, new Connection(42, "octo", 1001, "User", ADMIN, Instant.now()));
        connections.disconnect(42);
        assertThat(store.connected).isEmpty();
        assertThatThrownBy(() -> connections.disconnect(42)).isInstanceOf(ApiExceptions.NotFound.class);
    }

    // ---- fake GitHub and store --------------------------------------------------------------------

    private String state() {
        return query(connections.start(ADMIN)).get("state");
    }

    private void installations(String... installations) {
        respond("GET /api/v3/user/installations", 200, "{\"total_count\":" + installations.length + ",\"installations\":["
                + String.join(",", installations) + "]}");
    }

    private static String installation(long id, long accountId, String login, String type) {
        return "{\"id\":" + id + ",\"account\":{\"id\":" + accountId + ",\"login\":\"" + login + "\",\"type\":\"" + type + "\"}}";
    }

    private void respond(String route, int status, String body) {
        responses.put(route, new Response(status, body));
    }

    private Request request(String route) {
        return requests.stream().filter(request -> request.route().equals(route)).findFirst().orElseThrow();
    }

    private void respond(HttpExchange exchange) throws IOException {
        String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        requests.add(new Request(route, exchange.getRequestHeaders().getFirst("Authorization"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        Response response = responses.getOrDefault(route, new Response(404, "{}"));
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), response.status() == 204 ? -1 : body.length);
        if (response.status() != 204) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    private static Map<String, String> query(URI uri) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().toSingleValueMap().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> URLDecoder.decode(entry.getValue(), StandardCharsets.UTF_8)));
    }

    private static Map<String, String> form(String body) {
        Map<String, String> form = new HashMap<>();
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            form.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return form;
    }

    private record Request(String route, String authorization, String body) {
    }

    private record Response(int status, String body) {
    }

    /** One organization's rows, as the store scopes them to the caller's organization. */
    private static final class FakeStore implements GitHubConnectionStore {
        final Map<Long, Connection> connected = new HashMap<>();
        final Map<String, PendingConnection> pending = new HashMap<>();

        @Override
        public List<Connection> findAll() {
            return List.copyOf(connected.values());
        }

        @Override
        public boolean isConnected(long installationId) {
            return connected.containsKey(installationId);
        }

        @Override
        public boolean hasAny() {
            return !connected.isEmpty();
        }

        @Override
        public Connection connect(long installationId, String accountLogin, long accountId, String accountType,
                UUID connectedBy) {
            Connection connection = new Connection(installationId, accountLogin, accountId, accountType, connectedBy,
                    Instant.now());
            connected.put(installationId, connection);
            return connection;
        }

        @Override
        public boolean disconnect(long installationId) {
            return connected.remove(installationId) != null;
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
