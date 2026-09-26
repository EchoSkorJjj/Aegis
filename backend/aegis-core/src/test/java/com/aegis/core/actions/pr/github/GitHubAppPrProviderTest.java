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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.aegis.core.actions.pr.FileChange;
import com.aegis.core.actions.pr.PullRequestDraft;
import com.aegis.core.actions.pr.RepositoryFile;
import com.aegis.core.actions.pr.RepositoryRef;
import com.aegis.core.actions.pr.github.GitHubInstallationTokens.Access;
import com.aegis.core.inventory.domain.RepositoryUrl;
import com.aegis.core.inventory.sbom.RepositoryReadAccess.ReadGrant;
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
    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-0000-0000-00000000b002");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    @TempDir Path temporary;
    private final MutableClock clock = new MutableClock();
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<Throwable> observedErrors = new ArrayList<>();
    // Concurrent request handling, so one held-open request cannot block the fake GitHub itself.
    private final ExecutorService handlers = Executors.newCachedThreadPool();
    private volatile CountDownLatch slowArrived;
    private volatile CountDownLatch otherArrived;
    private volatile boolean slowReleasedByOther;
    private HttpServer server;
    private KeyPair keys;
    private GitHubAppPrProvider provider;
    private GitHubInstallationTokens tokens;
    private String account = "Sandbox";
    private UUID organization = ORG;
    /** Each organization's connected installations, as GitHubConnectionStore would report them. */
    private final Map<UUID, Set<Long>> connected = new ConcurrentHashMap<>();
    private long installationId = 42;
    private volatile int minted;
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
        server.setExecutor(handlers);
        server.start();
        connected.put(ORG, ConcurrentHashMap.newKeySet());
        connected.get(ORG).add(42L);
        configure();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        handlers.shutdownNow();
    }

    private void configure() throws Exception {
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
        tokens = new GitHubInstallationTokens(api, jwt, () -> !connectedTo(organization).isEmpty(),
                id -> connectedTo(organization).contains(id), clock);
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
        assertThat(tokens.forRepository("sandbox/repo", Access.PULL_REQUEST)).isEqualTo(TOKEN + "1");
        clock.now = clock.now.plusSeconds(55 * 60);
        assertThat(tokens.forRepository("sandbox/repo", Access.PULL_REQUEST)).isEqualTo(TOKEN + "1");
        clock.now = clock.now.plusSeconds(1);
        assertThat(tokens.forRepository("sandbox/repo", Access.PULL_REQUEST)).isEqualTo(TOKEN + "2");
        assertThat(tokens.forRepository("sandbox/other", Access.PULL_REQUEST)).isEqualTo(TOKEN + "3");
        installationId = 99;
        connected.get(ORG).add(99L);
        assertThat(tokens.forRepository("sandbox/repo", Access.PULL_REQUEST)).isEqualTo(TOKEN + "4");
        assertThat(minted).isEqualTo(4);
        assertThat(requests).filteredOn(request -> request.uri().getPath().endsWith("/installation")).hasSize(5);
    }

    @Test
    void aSlowLookupForOneRepositoryDoesNotHoldUpTokensForAnother() throws Exception {
        slowArrived = new CountDownLatch(1);
        otherArrived = new CountDownLatch(1);
        var slow = CompletableFuture.supplyAsync(() -> tokens.forRepository("sandbox/slow", Access.PULL_REQUEST));
        assertThat(slowArrived.await(5, TimeUnit.SECONDS)).isTrue();
        // The slow lookup is held open by the fake GitHub until this one arrives: under one lock
        // around the network calls it could not, and the slow lookup would give up after 5 s.
        assertThat(tokens.forRepository("sandbox/other", Access.PULL_REQUEST)).startsWith(TOKEN);
        assertThat(slow.get(10, TimeUnit.SECONDS)).startsWith(TOKEN);
        assertThat(slowReleasedByOther).isTrue();
    }

    @Test
    void expiredCacheEntriesAreReplaced() {
        tokens.forRepository("sandbox/repo", Access.PULL_REQUEST);
        clock.now = clock.now.plusSeconds(3601);
        assertThat(tokens.forRepository("sandbox/repo", Access.PULL_REQUEST)).isEqualTo(TOKEN + "2");
    }

    @Test
    void anOrganizationWithNothingConnectedIsRefusedWithoutAnyHttpCall() {
        connected.clear();
        assertThatThrownBy(() -> provider.openDraftPullRequest(draft(null)))
                .isInstanceOf(ApiExceptions.Forbidden.class)
                .hasMessage("GitHub isn't connected to this organization; " + GitHubInstallationTokens.CONNECT_HINT);
        assertThat(requests).isEmpty();
    }

    @Test
    void anInstallationTheOrganizationDidNotConnectIsRefusedBeforeMintingAToken() {
        installationId = 43;
        assertThatThrownBy(() -> provider.openDraftPullRequest(draft(null)))
                .isInstanceOf(ApiExceptions.Forbidden.class)
                .hasMessageContaining("The Aegis GitHub App on Sandbox isn't connected to this organization");
        assertThat(requests).hasSize(1);
        assertThat(minted).isZero();
    }

    @Test
    void theConnectionIsRecheckedBeforeReusingACachedToken() {
        tokens.forRepository("sandbox/repo", Access.PULL_REQUEST);
        connected.get(ORG).add(7L); // still connected to something, just not this installation
        connected.get(ORG).remove(42L);
        assertThatThrownBy(() -> tokens.forRepository("sandbox/repo", Access.PULL_REQUEST)).isInstanceOf(ApiExceptions.Forbidden.class);
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
        // Not this call's branch, so it is never cleaned up.
        assertThat(requests).noneMatch(request -> request.method().equals("DELETE"));
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
        assertThatThrownBy(() -> tokens.forRepository("sandbox/repo", Access.PULL_REQUEST))
                .isInstanceOf(ApiExceptions.UpstreamUnavailable.class).hasNoCause().hasMessageNotContaining(TOKEN);
        assertObservedErrorsAreSafe();
        failurePath = null;
        assertThat(tokens.forRepository("sandbox/repo", Access.PULL_REQUEST)).isEqualTo(TOKEN + "1");
    }

    @Test
    void incompleteTokenResponseIsRejected() {
        failurePath = "/access_tokens";
        failureStatus = 201;
        failureBody = "{\"token\":\"" + TOKEN + "1\"}";
        assertThatThrownBy(() -> tokens.forRepository("sandbox/repo", Access.PULL_REQUEST))
                .isInstanceOf(ApiExceptions.UpstreamUnavailable.class).hasMessageNotContaining(TOKEN);
    }

    @Test
    void tokenContainingHeaderControlCharactersIsRejectedSafely() {
        failurePath = "/access_tokens";
        failureStatus = 201;
        failureBody = "{\"token\":\"" + TOKEN + "\\r\\nInjected: secret\",\"expires_at\":\""
                + clock.instant().plusSeconds(3600) + "\"}";
        assertThatThrownBy(() -> tokens.forRepository("sandbox/repo", Access.PULL_REQUEST))
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
        assertThat(requests).hasSize(8);
        assertThat(requests.get(6).method()).isEqualTo("PUT");
        assertThat(requests.get(6).uri().getPath()).endsWith("/contents/pom.xml");
        // The branch this call created is deleted, so the same draft can be retried.
        assertThat(requests.getLast().method() + " " + requests.getLast().uri().getRawPath())
                .isEqualTo("DELETE /api/v3/repos/sandbox/repo/git/refs/heads/aegis/upgrade");
        assertThat(requests.getLast().authorization()).isEqualTo("Bearer " + TOKEN + "1");
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
        assertThat(requests).hasSize(7);
        assertThat(requests.getLast().method()).isEqualTo("DELETE");
    }

    @Test
    void readsAFileAndItsRevisionFromTheRequestedBranch() {
        String pom = "<project>\n  <version>1.0.0</version>\n</project>\n";
        serveFile("pom.xml", pom.getBytes(StandardCharsets.UTF_8));

        RepositoryFile file = provider.readFile(repository(), "develop", "pom.xml");

        assertThat(file.content()).isEqualTo(pom);
        assertThat(file.revision()).isEqualTo(BLOB);
        assertThat(requests).extracting(request -> request.method() + " " + request.uri().getRawPath()).containsExactly(
                "GET /api/v3/repos/sandbox/repo/installation",
                "POST /api/v3/app/installations/42/access_tokens",
                "GET /api/v3/repos/sandbox/repo/contents/pom.xml");
        assertThat(requests.getLast().uri().getQuery()).isEqualTo("ref=develop");
    }

    @Test
    void readingWithoutABranchUsesTheRepositoryDefault() {
        serveFile("pom.xml", "<project/>".getBytes(StandardCharsets.UTF_8));
        provider.readFile(repository(), null, "pom.xml");
        assertThat(requests.getLast().uri().getQuery()).isNull();
    }

    @Test
    void readingRefusesWhatCannotBeEditedSafely() {
        failurePath = "/contents/pom.xml";
        failureMethod = "GET";
        failureStatus = 200;
        failureBody = "{\"type\":\"file\",\"sha\":\"" + BLOB + "\",\"encoding\":\"none\",\"content\":\"\"}";
        assertThatThrownBy(() -> provider.readFile(repository(), null, "pom.xml"))
                .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("too large");

        serveFile("pom.xml", new byte[] {(byte) 0xff, (byte) 0xfe, 'x'});
        assertThatThrownBy(() -> provider.readFile(repository(), null, "pom.xml"))
                .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("not UTF-8");

        failureBody = "[{\"type\":\"file\",\"name\":\"a.xml\"}]";
        assertThatThrownBy(() -> provider.readFile(repository(), null, "pom.xml"))
                .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("not a regular file");

        failureStatus = 404;
        failureBody = "{\"message\":\"Not Found\"}";
        assertThatThrownBy(() -> provider.readFile(repository(), null, "pom.xml"))
                .isInstanceOf(ApiExceptions.NotFound.class).hasMessage("pom.xml was not found in the repository");
    }

    @Test
    void anExpectedRevisionIsWrittenAsTheBlobShaWithoutReReadingTheFile() {
        var draft = new PullRequestDraft(repository(), "main", "aegis/upgrade", "Upgrade", "Reviewed", "Upgrade dependency",
                List.of(new FileChange("pom.xml", "<project/>", BLOB)));

        provider.openDraftPullRequest(draft);

        assertThat(requests).extracting(request -> request.method() + " " + request.uri().getRawPath()).containsExactly(
                "GET /api/v3/repos/sandbox/repo/installation",
                "POST /api/v3/app/installations/42/access_tokens",
                "GET /api/v3/repos/sandbox/repo/git/ref/heads/main",
                "POST /api/v3/repos/sandbox/repo/git/refs",
                "PUT /api/v3/repos/sandbox/repo/contents/pom.xml",
                "POST /api/v3/repos/sandbox/repo/pulls");
        assertThat(requests.get(4).json().path("sha").asText()).isEqualTo(BLOB);
    }

    @Test
    void aFileThatChangedSinceItWasReadFailsAndCleansUp() {
        failurePath = "/contents/pom.xml";
        failureMethod = "PUT";
        failureStatus = 409;
        var draft = new PullRequestDraft(repository(), "main", "aegis/upgrade", "Upgrade", "Reviewed", "Upgrade dependency",
                List.of(new FileChange("pom.xml", "<project/>", BLOB)));

        assertThatThrownBy(() -> provider.openDraftPullRequest(draft)).isInstanceOf(ApiExceptions.Conflict.class);
        assertThat(requests.getLast().method()).isEqualTo("DELETE");
    }

    @Test
    void aRevisionThatIsNotABlobShaIsRefusedBeforeAnyCall() {
        var draft = new PullRequestDraft(repository(), "main", "aegis/upgrade", "Upgrade", "Reviewed", "Upgrade dependency",
                List.of(new FileChange("pom.xml", "<project/>", "v1")));

        assertThatThrownBy(() -> provider.openDraftPullRequest(draft)).isInstanceOf(ApiExceptions.BadRequest.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void directoryTargetIsAConflictNotAnUpstreamFailure() {
        // GitHub lists a directory as a JSON array rather than describing one file.
        failurePath = "/contents/pom.xml";
        failureMethod = "GET";
        failureStatus = 200;
        failureBody = "[{\"type\":\"file\",\"name\":\"a.xml\",\"sha\":\"" + BLOB + "\"}]";
        assertThatThrownBy(() -> provider.openDraftPullRequest(draft(null)))
                .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("regular file");
        assertThat(requests.getLast().method()).isEqualTo("DELETE");
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

    private static RepositoryRef repository() {
        return new RepositoryRef("github.com", "sandbox/repo");
    }

    /** Answer the Contents GET for {@code path} the way GitHub does: base64 in 60-column lines. */
    @Test
    void readAccessMintsAReadOnlyTokenScopedToTheOneRepository() throws Exception {
        ReadGrant grant = readAccess().grant(github("https://github.com/sandbox/repo"));

        assertThat(grant.credential().username()).isEqualTo("x-access-token");
        assertThat(grant.credential().secret()).isEqualTo(TOKEN + "1");
        assertThat(grant.via()).isEqualTo("the Aegis GitHub App");
        assertThat(requests.get(1).json()).isEqualTo(MAPPER.readTree(
                "{\"repositories\":[\"repo\"],\"permissions\":{\"contents\":\"read\"}}"));
    }

    @Test
    void readAndPullRequestTokensAreMintedAndCachedSeparately() {
        assertThat(tokens.forRepository("sandbox/repo", Access.PULL_REQUEST)).isEqualTo(TOKEN + "1");
        assertThat(tokens.forRepository("sandbox/repo", Access.READ)).isEqualTo(TOKEN + "2");
        assertThat(tokens.forRepository("sandbox/repo", Access.READ)).isEqualTo(TOKEN + "2");
        assertThat(tokens.forRepository("sandbox/repo", Access.PULL_REQUEST)).isEqualTo(TOKEN + "1");
        assertThat(minted).isEqualTo(2);
    }

    @Test
    void readAccessExplainsWhyItCannotHelpInsteadOfFailingTheImport() throws Exception {
        failurePath = "/installation";
        failureStatus = 404;
        assertThat(readAccess().grant(github("https://github.com/sandbox/repo")).unavailable())
                .isEqualTo("the Aegis GitHub App is not installed on sandbox or can't reach this repository");

        failurePath = null;
        installationId = 43;
        assertThat(readAccess().grant(github("https://github.com/sandbox/repo")).unavailable())
                .isEqualTo("the Aegis GitHub App on sandbox isn't connected to this organization; "
                        + GitHubInstallationTokens.CONNECT_HINT);

        failurePath = "/installation";
        failureStatus = 500;
        assertThat(readAccess().grant(github("https://github.com/sandbox/repo")).unavailable())
                .isEqualTo("GitHub could not be reached to use the Aegis GitHub App");
        assertThat(minted).isZero();
    }

    @Test
    void readAccessLeavesOtherHostsAndPathsAlone() {
        ReadGrant gitlab = readAccess().grant(RepositoryUrl.parse("https://gitlab.com/group/sub/repo", List.of("gitlab.com")));
        assertThat(gitlab).isEqualTo(ReadGrant.notCovered());
        assertThat(requests).isEmpty();
    }

    @Test
    void anInstallationOneOrganizationConnectedIsRefusedToAnother() {
        assertThat(tokens.forRepository("sandbox/repo", Access.READ)).isEqualTo(TOKEN + "1");

        organization = OTHER_ORG; // nothing connected
        assertThatThrownBy(() -> tokens.forRepository("sandbox/repo", Access.READ))
                .isInstanceOf(ApiExceptions.Forbidden.class).hasMessageContaining("isn't connected to this organization");
        connected.put(OTHER_ORG, Set.of(7L)); // something else connected
        assertThatThrownBy(() -> provider.openDraftPullRequest(draft(null)))
                .isInstanceOf(ApiExceptions.Forbidden.class).hasMessageContaining("isn't connected to this organization");
        assertThat(readAccess().grant(github("https://github.com/sandbox/repo")).credential()).isNull();
        assertThat(minted).isEqualTo(1);
    }

    @Test
    void severalOrganizationsCanEachConnectTheSameInstallation() {
        connected.put(OTHER_ORG, Set.of(42L));
        assertThat(tokens.forRepository("sandbox/repo", Access.READ)).isNotBlank();
        organization = OTHER_ORG;
        assertThat(tokens.forRepository("sandbox/repo", Access.READ)).isNotBlank();
    }

    private Set<Long> connectedTo(UUID org) {
        return connected.getOrDefault(org, Set.of());
    }

    private GitHubRepositoryReadAccess readAccess() {
        return new GitHubRepositoryReadAccess(tokens, Set.of("github.com"));
    }

    private static RepositoryUrl github(String url) {
        return RepositoryUrl.parse(url, List.of("github.com"));
    }

    private void serveFile(String path, byte[] bytes) {
        failurePath = "/contents/" + path;
        failureMethod = "GET";
        failureStatus = 200;
        // A JSON-escaped newline every 60 characters, as GitHub sends it.
        String wrapped = String.join("\\n", Base64.getEncoder().encodeToString(bytes).split("(?<=\\G.{60})"));
        failureBody = "{\"type\":\"file\",\"sha\":\"" + BLOB + "\",\"encoding\":\"base64\",\"content\":\"" + wrapped + "\"}";
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
            holdSlowLookupUntilAnotherArrives(path);
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

    /** When armed, the sandbox/slow lookup waits (up to 5 s) for a lookup of another repository. */
    private void holdSlowLookupUntilAnotherArrives(String path) {
        if (otherArrived == null) {
            return;
        }
        if (path.contains("/sandbox/slow/")) {
            slowArrived.countDown();
            try {
                slowReleasedByOther = otherArrived.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        } else {
            otherArrived.countDown();
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
