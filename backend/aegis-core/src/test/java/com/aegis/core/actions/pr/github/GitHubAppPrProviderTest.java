package com.aegis.core.actions.pr.github;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.aegis.core.actions.pr.FileChange;
import com.aegis.core.actions.pr.PullRequestDraft;
import com.aegis.core.actions.pr.RepositoryRef;
import com.aegis.core.platform.common.ApiExceptions;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class GitHubAppPrProviderTest {
    private static final String SHA = "a".repeat(40);
    private static final String BLOB = "b".repeat(40);
    private static final String TOKEN = "test-installation-secret-";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    @TempDir Path temporary;
    private final MutableClock clock = new MutableClock();
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<Throwable> observedErrors = new ArrayList<>();
    private HttpServer server;
    private KeyPair keys;
    private GitHubAppPrProvider provider;
    private GitHubInstallationTokens tokens;
    private String account = "Sandbox";
    private long installationId = 42;
    private int minted;
    private String defaultBranch = "main";
    private String failurePath;
    private String failureMethod;
    private int failureStatus = 500;
    private String failureBody;
    private String contentType = "file";

    @BeforeEach
    void setUp() throws Exception {
        keys = generateKey();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        configure(Set.of("sandbox"));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void configure(Set<String> accounts) throws Exception {
        Path key = writeKey(temporary, keys);
        var jwt = new GitHubAppJwt(key, "test-client-id", clock);
        var observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onError(Observation.Context context) {
                observedErrors.add(context.getError());
            }
        });
        var api = new GitHubApi(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3"),
                MAPPER, observations);
        tokens = new GitHubInstallationTokens(api, jwt, accounts, clock);
        provider = new GitHubAppPrProvider(api, tokens, Set.of("github.com"));
    }

    @Test
    void signsJwtScopesTokenAndOpensDraftWithExistingAndNewFiles() throws Exception {
        var result = provider.openDraftPullRequest(draft(null));
        assertThat(result.provider()).isEqualTo("github");
        assertThat(result.number()).isEqualTo("7");
        assertThat(result.url()).isEqualTo("https://github.com/sandbox/repo/pull/7");
        assertThat(result.headBranch()).isEqualTo("aegis/upgrade");
        assertThat(requests).extracting(request -> request.method() + " " + request.uri().getRawPath()).containsExactly(
                "GET /api/v3/repos/sandbox/repo/installation",
                "POST /api/v3/app/installations/42/access_tokens",
                "GET /api/v3/repos/sandbox/repo",
                "GET /api/v3/repos/sandbox/repo/git/ref/heads/main",
                "POST /api/v3/repos/sandbox/repo/git/refs",
                "GET /api/v3/repos/sandbox/repo/contents/pom.xml",
                "PUT /api/v3/repos/sandbox/repo/contents/pom.xml",
                "GET /api/v3/repos/sandbox/repo/contents/notes/new%20%23%3F%25.txt",
                "PUT /api/v3/repos/sandbox/repo/contents/notes/new%20%23%3F%25.txt",
                "POST /api/v3/repos/sandbox/repo/pulls");
        for (int i = 0; i < requests.size(); i++) {
            Request request = requests.get(i);
            assertThat(request.accept()).isEqualTo("application/vnd.github+json");
            assertThat(request.version()).isEqualTo("2026-03-10");
            if (i < 2) {
                var jwt = SignedJWT.parse(request.authorization().substring("Bearer ".length()));
                assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) keys.getPublic()))).isTrue();
                assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
                assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("test-client-id");
                assertThat(jwt.getJWTClaimsSet().getIssueTime().toInstant()).isEqualTo(clock.instant().minusSeconds(60));
                assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant()).isEqualTo(clock.instant().plusSeconds(540));
            } else {
                assertThat(request.authorization()).isEqualTo("Bearer " + TOKEN + "1");
            }
        }
        assertThat(requests.get(1).json()).isEqualTo(MAPPER.readTree(
                "{\"repositories\":[\"repo\"],\"permissions\":{\"contents\":\"write\",\"pull_requests\":\"write\"}}"));
        assertThat(requests.get(4).json()).isEqualTo(MAPPER.readTree(
                "{\"ref\":\"refs/heads/aegis/upgrade\",\"sha\":\"" + SHA + "\"}"));
        assertThat(requests.get(5).uri().getQuery()).isEqualTo("ref=aegis/upgrade");
        assertThat(requests.get(6).json().path("sha").asText()).isEqualTo(BLOB);
        assertThat(requests.get(6).json().path("branch").asText()).isEqualTo("aegis/upgrade");
        assertThat(requests.get(6).json().path("message").asText()).isEqualTo("Upgrade dependency");
        assertThat(new String(Base64.getDecoder().decode(requests.get(8).json().path("content").asText()),
                StandardCharsets.UTF_8)).isEqualTo("Updated ✓\n");
        assertThat(requests.get(8).json().has("sha")).isFalse();
        assertThat(requests.get(9).json()).isEqualTo(MAPPER.readTree(
                "{\"title\":\"Upgrade\",\"head\":\"aegis/upgrade\",\"base\":\"main\",\"body\":\"Reviewed proposal\",\"draft\":true}"));
    }

    @Test
    void explicitBaseSkipsDefaultLookupAndSupportsSlashInBranch() {
        provider.openDraftPullRequest(draft("release/1.0"));
        assertThat(requests).noneMatch(request -> request.uri().getPath().equals("/api/v3/repos/sandbox/repo"));
        assertThat(requests.get(2).uri().getPath()).endsWith("/git/ref/heads/release/1.0");
        assertThat(requests.getLast().json().path("base").asText()).isEqualTo("release/1.0");
    }

    @Test
    void cachesByInstallationAndRepositoryAndRefreshesBelowFiveMinutes() {
        assertThat(tokens.forRepository("sandbox/repo")).isEqualTo(TOKEN + "1");
        clock.now = clock.now.plusSeconds(55 * 60);
        assertThat(tokens.forRepository("sandbox/repo")).isEqualTo(TOKEN + "1");
        clock.now = clock.now.plusSeconds(1);
        assertThat(tokens.forRepository("sandbox/repo")).isEqualTo(TOKEN + "2");
        assertThat(tokens.forRepository("sandbox/other")).isEqualTo(TOKEN + "3");
        installationId = 99;
        assertThat(tokens.forRepository("sandbox/repo")).isEqualTo(TOKEN + "4");
        assertThat(minted).isEqualTo(4);
        assertThat(requests).filteredOn(request -> request.uri().getPath().endsWith("/installation")).hasSize(5);
    }

    @Test
    void expiredCacheEntriesAreReplaced() {
        tokens.forRepository("sandbox/repo");
        clock.now = clock.now.plusSeconds(3601);
        assertThat(tokens.forRepository("sandbox/repo")).isEqualTo(TOKEN + "2");
    }

    @Test
    void emptyAllowListRefusesWithoutAnyHttpCall() throws Exception {
        configure(Set.of());
        assertThatThrownBy(() -> provider.openDraftPullRequest(draft(null)))
                .isInstanceOf(ApiExceptions.Forbidden.class).hasMessageContaining("allowed-accounts");
        assertThat(requests).isEmpty();
    }

    @Test
    void disallowedAccountRefusesBeforeMintingToken() {
        account = "somebody-else";
        assertThatThrownBy(() -> provider.openDraftPullRequest(draft(null)))
                .isInstanceOf(ApiExceptions.Forbidden.class).hasMessageContaining("allowed-accounts");
        assertThat(requests).hasSize(1);
        assertThat(minted).isZero();
    }

    @Test
    void accountIsRecheckedBeforeReusingCachedToken() {
        tokens.forRepository("sandbox/repo");
        account = "somebody-else";
        assertThatThrownBy(() -> tokens.forRepository("sandbox/repo")).isInstanceOf(ApiExceptions.Forbidden.class);
        assertThat(minted).isEqualTo(1);
    }

    @Test
    void missingInstallationHasActionableError() {
        failurePath = "/installation";
        failureStatus = 404;
        assertThatThrownBy(() -> provider.openDraftPullRequest(draft(null)))
                .isInstanceOf(ApiExceptions.NotFound.class)
                .hasMessage("the Aegis GitHub App is not installed on sandbox/repo");
        assertThat(minted).isZero();
    }

    @Test
    void existingBranchStopsBeforeFileWrites() {
        failurePath = "/git/refs";
        failureStatus = 422;
        assertThatThrownBy(() -> provider.openDraftPullRequest(draft(null)))
                .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("branch may already exist");
        assertThat(requests).hasSize(5);
    }

    @Test
    void defaultEqualToHeadRefusesBeforeAnyRepositoryWrite() {
        defaultBranch = "aegis/upgrade";
        assertThatThrownBy(() -> provider.openDraftPullRequest(draft(null)))
                .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("distinct");
        assertThat(requests).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 500, 302})
    void upstreamFailuresDiscardSecretsAndDoNotFollowRedirectsOrRetry(int status) {
        failurePath = "/git/ref/heads/main";
        failureStatus = status;
        var error = catchThrowable(() -> provider.openDraftPullRequest(draft(null)));
        assertThat(error).isInstanceOf(ApiExceptions.UpstreamUnavailable.class).hasNoCause();
        assertThat(error.toString()).doesNotContain(TOKEN, "Bearer", "upstream-secret");
        assertThat(requests).hasSize(4);
    }

    @Test
    void malformedSuccessResponseDoesNotLeakToken() {
        failurePath = "/git/ref/heads/main";
        failureStatus = 200;
        failureBody = "malformed " + TOKEN + "1";
        assertThatThrownBy(() -> provider.openDraftPullRequest(draft(null)))
                .isInstanceOf(ApiExceptions.UpstreamUnavailable.class).hasNoCause()
                .hasMessageNotContaining(TOKEN);
        assertThat(requests).hasSize(4);
        assertObservedErrorsAreSafe();
    }

    @Test
    void malformedExpiryDoesNotLeakTokenOrEnterCache() {
        failurePath = "/access_tokens";
        failureStatus = 201;
        failureBody = "{\"token\":\"" + TOKEN + "1\",\"expires_at\":\"" + TOKEN + "1\"}";
        assertThatThrownBy(() -> tokens.forRepository("sandbox/repo"))
                .isInstanceOf(ApiExceptions.UpstreamUnavailable.class).hasNoCause().hasMessageNotContaining(TOKEN);
        assertObservedErrorsAreSafe();
        failurePath = null;
        assertThat(tokens.forRepository("sandbox/repo")).isEqualTo(TOKEN + "1");
    }

    @Test
    void incompleteTokenResponseIsRejected() {
        failurePath = "/access_tokens";
        failureStatus = 201;
        failureBody = "{\"token\":\"" + TOKEN + "1\"}";
        assertThatThrownBy(() -> tokens.forRepository("sandbox/repo"))
                .isInstanceOf(ApiExceptions.UpstreamUnavailable.class).hasMessageNotContaining(TOKEN);
    }

    @Test
    void tokenContainingHeaderControlCharactersIsRejectedSafely() {
        failurePath = "/access_tokens";
        failureStatus = 201;
        failureBody = "{\"token\":\"" + TOKEN + "\\r\\nInjected: secret\",\"expires_at\":\""
                + clock.instant().plusSeconds(3600) + "\"}";
        assertThatThrownBy(() -> tokens.forRepository("sandbox/repo"))
                .isInstanceOf(ApiExceptions.UpstreamUnavailable.class).hasNoCause().hasMessageNotContaining(TOKEN);
        assertThat(requests).hasSize(2);
    }

    @Test
    void acceptsCanonicalRepositoryCasingInPullRequestUrl() {
        failurePath = "/pulls";
        failureStatus = 201;
        failureBody = "{\"html_url\":\"https://github.com/Sandbox/Repo/pull/7\",\"number\":7}";
        assertThat(provider.openDraftPullRequest(draft(null)).url()).isEqualTo("https://github.com/Sandbox/Repo/pull/7");
    }

    @Test
    void rejectsUnexpectedPullRequestUrlWithoutExposingItsContents() {
        failurePath = "/pulls";
        failureStatus = 201;
        failureBody = "{\"html_url\":\"https://untrusted.example/" + TOKEN + "1\",\"number\":7}";
        assertThatThrownBy(() -> provider.openDraftPullRequest(draft(null)))
                .isInstanceOf(ApiExceptions.UpstreamUnavailable.class).hasMessageNotContaining(TOKEN);
    }

    @Test
    void failedFileWriteIsNotRetriedAndDoesNotOpenPullRequest() {
        failurePath = "/contents/pom.xml";
        failureMethod = "PUT";
        failureStatus = 403;
        assertThatThrownBy(() -> provider.openDraftPullRequest(draft(null)))
                .isInstanceOf(ApiExceptions.UpstreamUnavailable.class);
        assertThat(requests).hasSize(7);
        assertThat(requests.getLast().method()).isEqualTo("PUT");
        assertThat(requests.getLast().uri().getPath()).endsWith("/contents/pom.xml");
    }

    @Test
    void missingBaseIsNotTreatedAsNewFile() {
        failurePath = "/git/ref/heads/main";
        failureStatus = 404;
        assertThatThrownBy(() -> provider.openDraftPullRequest(draft(null)))
                .isInstanceOf(ApiExceptions.NotFound.class).hasMessageContaining("not found");
        assertThat(requests).hasSize(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dir", "submodule", "symlink"})
    void nonRegularFilesAreNotOverwritten(String type) {
        contentType = type;
        assertThatThrownBy(() -> provider.openDraftPullRequest(draft(null)))
                .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("regular file");
        assertThat(requests).hasSize(6);
    }

    @Test
    void networkFailureHasSafeError() {
        server.stop(0);
        assertThatThrownBy(() -> provider.openDraftPullRequest(draft(null)))
                .isInstanceOf(ApiExceptions.UpstreamUnavailable.class).hasNoCause();
    }

    @Test
    void supportsOnlyConfiguredHostsAndOwnerRepoPaths() {
        assertThat(provider.supports(new RepositoryRef("github.com", "sandbox/repo"))).isTrue();
        assertThat(provider.supports(new RepositoryRef("gitlab.com", "sandbox/repo"))).isFalse();
        var unsupported = new RepositoryRef("github.com", "group/subgroup/repo");
        assertThat(provider.supports(unsupported)).isFalse();
        assertThatThrownBy(() -> provider.openDraftPullRequest(new PullRequestDraft(unsupported, "main", "aegis/test",
                "Test", "", "Test", List.of(new FileChange("pom.xml", "test")))))
                .isInstanceOf(ApiExceptions.Conflict.class);
        assertThat(requests).isEmpty();
    }

    private PullRequestDraft draft(String base) {
        return new PullRequestDraft(new RepositoryRef("github.com", "sandbox/repo"), base, "aegis/upgrade",
                "Upgrade", "Reviewed proposal", "Upgrade dependency",
                List.of(new FileChange("pom.xml", "<project/>"), new FileChange("notes/new #?%.txt", "Updated ✓\n")));
    }

    private void assertObservedErrorsAreSafe() {
        assertThat(observedErrors).isNotEmpty().allSatisfy(error -> {
            assertThat(error).isInstanceOf(ApiExceptions.UpstreamUnavailable.class).hasNoCause();
            assertThat(error.toString()).doesNotContain(TOKEN, "Bearer", "upstream-secret");
        });
    }

    private void respond(HttpExchange exchange) throws IOException {
        var headers = exchange.getRequestHeaders();
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI(),
                headers.getFirst("Authorization"), headers.getFirst("Accept"), headers.getFirst("X-GitHub-Api-Version"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        String path = exchange.getRequestURI().getPath();
        int status = 200;
        String body;
        if (failurePath != null && path.endsWith(failurePath)
                && (failureMethod == null || failureMethod.equals(exchange.getRequestMethod()))) {
            status = failureStatus;
            body = failureBody == null ? "{\"message\":\"upstream-secret " + headers.getFirst("Authorization") + "\"}" : failureBody;
            if (status == 302) {
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/stolen");
            }
        } else if (path.endsWith("/installation")) {
            body = "{\"id\":" + installationId + ",\"account\":{\"login\":\"" + account + "\"},\"extra\":true}";
        } else if (path.endsWith("/access_tokens")) {
            minted++;
            status = 201;
            body = "{\"token\":\"" + TOKEN + minted + "\",\"expires_at\":\"" + clock.instant().plusSeconds(3600) + "\"}";
        } else if (path.endsWith("/repos/sandbox/repo")) {
            body = "{\"default_branch\":\"" + defaultBranch + "\",\"unknown\":123}";
        } else if (path.contains("/git/ref/heads/")) {
            body = "{\"object\":{\"sha\":\"" + SHA + "\"}}";
        } else if (path.contains("/contents/") && exchange.getRequestMethod().equals("GET")) {
            if (path.endsWith("pom.xml")) {
                body = "{\"sha\":\"" + BLOB + "\",\"type\":\"" + contentType + "\"}";
            } else {
                status = 404;
                body = "{\"message\":\"Not Found\"}";
            }
        } else if (path.endsWith("/pulls")) {
            status = 201;
            body = "{\"html_url\":\"https://github.com/sandbox/repo/pull/7\",\"number\":7,\"draft\":true}";
        } else {
            status = 201;
            body = "{}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    static KeyPair generateKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    static Path writeKey(Path directory, KeyPair keys) throws IOException {
        return Files.writeString(directory.resolve("test-app.pkcs8.pem"), "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
    }

    private record Request(String method, URI uri, String authorization, String accept, String version, String body) {
        JsonNode json() {
            return MAPPER.readTree(body);
        }
    }

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-25T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
