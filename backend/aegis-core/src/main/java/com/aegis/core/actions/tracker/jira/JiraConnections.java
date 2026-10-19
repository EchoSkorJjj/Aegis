package com.aegis.core.actions.tracker.jira;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.aegis.core.actions.tracker.jira.JiraApi.Project;
import com.aegis.core.actions.tracker.jira.JiraApi.Tokens;
import com.aegis.core.actions.tracker.jira.JiraConnectionStore.Connection;
import com.aegis.core.actions.tracker.jira.JiraConnectionStore.PendingConnection;
import com.aegis.core.actions.tracker.jira.JiraConnectionStore.SealedTokens;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.crypto.SecretBox;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Connect Jira: the one Jira site an organization files its risk tickets in (V24). Its organization admin
 * starts ({@link #start}) and is sent to Atlassian to grant Aegis a site, with a one-time state bound to
 * them and their organization; {@link #complete} keeps the tokens Atlassian gives — sealed, bound to the
 * organization — and the admin then chooses the project and issue type ({@link #configure}). Access tokens
 * last an hour; {@link #accessToken} renews them under the connection's lock, since Atlassian spends the
 * refresh token on every renewal.
 */
final class JiraConnections {

    /** How long a started connection may take to come back from Atlassian. */
    static final Duration PENDING_FOR = Duration.ofMinutes(10);
    /** Renew an access token this long before it expires, so none expires mid-request. */
    static final Duration RENEW_BEFORE = Duration.ofMinutes(2);
    static final String WRITE_SCOPE = "write:jira-work";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JiraProperties properties;
    private final JiraApi api;
    private final JiraConnectionStore store;
    private final SecretBox box;
    private final Supplier<UUID> organization;
    private final Clock clock;

    JiraConnections(JiraProperties properties, JiraApi api, JiraConnectionStore store, SecretBox box,
            Supplier<UUID> organization, Clock clock) {
        this.properties = properties;
        this.api = api;
        this.store = store;
        this.box = box;
        this.organization = organization;
        this.clock = clock;
    }

    /** A Jira site the connection reaches. */
    record Site(String cloudId, String name, String url) {
    }

    /** What an admin can choose from: the sites, the chosen site's projects, and the chosen project's issue types. */
    record Options(List<Site> sites, String cloudId, List<Project> projects, List<String> issueTypes) {
    }

    Optional<Connection> connection() {
        return store.find();
    }

    /** The Atlassian consent screen to send {@code userId} to, carrying a state only they and their organization can complete. */
    URI start(UUID userId) {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Instant now = clock.instant();
        store.startConnecting(hash(state), userId, now.plus(PENDING_FOR), now);
        return UriComponentsBuilder.fromUri(properties.authBaseUrl()).path("/authorize")
                .queryParam("audience", "api.atlassian.com")
                .queryParam("client_id", properties.clientId())
                .queryParam("scope", JiraProperties.SCOPES)
                .queryParam("redirect_uri", properties.callbackUrl().toString())
                .queryParam("state", state)
                .queryParam("response_type", "code")
                .queryParam("prompt", "consent")
                .build().encode().toUri();
    }

    /**
     * Atlassian sent {@code userId} back with this code and state: keep the tokens and the first site they
     * granted (the admin can pick another). The state must be one this person started in this organization,
     * unexpired and unused.
     */
    Connection complete(UUID userId, String code, String state) {
        Optional<PendingConnection> pending = store.takePending(hash(state));
        if (pending.isEmpty() || !pending.get().userId().equals(userId)
                || !pending.get().expiresAt().isAfter(clock.instant())) {
            throw new ApiExceptions.Forbidden(
                    "This Jira sign-in wasn't started by you in this organization, or it expired; connect again");
        }
        Tokens tokens = api.exchange(code);
        Site site = sites(tokens.accessToken()).stream().findFirst().orElseThrow(() -> new ApiExceptions.Conflict(
                "That Atlassian account granted Aegis no Jira site it can create issues in; connect again and choose one"));
        return store.connect(site.cloudId(), site.name(), site.url(), seal(tokens), userId);
    }

    /** The sites, the projects of {@code cloudId} (else the connected site), and the issue types of {@code projectKey}. */
    Options options(String cloudId, String projectKey) {
        Connection connection = store.find().orElseThrow(JiraConnections::notConnected);
        String token = accessToken();
        List<Site> sites = sites(token);
        Site site = site(sites, cloudId == null || cloudId.isBlank() ? connection.cloudId() : cloudId);
        List<Project> projects = api.projects(site.cloudId(), token);
        List<String> issueTypes = projectKey == null || projectKey.isBlank() ? List.of()
                : api.issueTypes(site.cloudId(), token, projectKey).stream().map(JiraApi.IssueType::name).toList();
        return new Options(sites, site.cloudId(), projects, issueTypes);
    }

    /** Where risk tickets go: a site the connection reaches, a project there, and one of its issue types. */
    Connection configure(String cloudId, String projectKey, String issueType) {
        JiraApi.requireProjectKey(projectKey);
        String token = accessToken();
        Site site = site(sites(token), cloudId);
        List<String> issueTypes = api.issueTypes(site.cloudId(), token, projectKey).stream()
                .map(JiraApi.IssueType::name).toList();
        if (!issueTypes.contains(issueType)) {
            throw new ApiExceptions.BadRequest("\"" + issueType + "\" isn't an issue type of " + projectKey);
        }
        return store.configure(site.cloudId(), site.name(), site.url(), projectKey, issueType);
    }

    void disconnect() {
        if (!store.disconnect()) {
            throw new ApiExceptions.NotFound("Jira isn't connected to this organization");
        }
    }

    /** An access token for the caller's organization, renewed first when it is about to expire. */
    String accessToken() {
        SealedTokens sealed = store.tokens().orElseThrow(JiraConnections::notConnected);
        if (fresh(sealed)) {
            return box.open(sealed.accessToken(), context());
        }
        return box.open(store.renewTokens(current -> fresh(current) ? current : renewed(current)).accessToken(),
                context());
    }

    /** A new access token now, whatever the stored one's expiry: Jira has just refused it. */
    String renewedAccessToken() {
        return box.open(store.renewTokens(this::renewed).accessToken(), context());
    }

    private SealedTokens renewed(SealedTokens current) {
        Tokens tokens = api.refresh(box.open(current.refreshToken(), context()));
        // Rotating refresh tokens come back with every renewal; keep the old one if this one didn't.
        return tokens.refreshToken() == null
                ? new SealedTokens(box.seal(tokens.accessToken(), context()),
                        clock.instant().plusSeconds(tokens.expiresIn()), current.refreshToken())
                : seal(tokens);
    }

    private boolean fresh(SealedTokens tokens) {
        return tokens.accessExpiresAt().isAfter(clock.instant().plus(RENEW_BEFORE));
    }

    private SealedTokens seal(Tokens tokens) {
        return new SealedTokens(box.seal(tokens.accessToken(), context()),
                clock.instant().plusSeconds(tokens.expiresIn()), box.seal(tokens.refreshToken(), context()));
    }

    /** Sealed values are bound to the organization: one copied to another organization's row won't open. */
    private String context() {
        return "jira:" + organization.get();
    }

    /** The granted sites Aegis may create issues in, each with an https address. */
    private List<Site> sites(String accessToken) {
        return api.sites(accessToken).stream()
                .filter(site -> site.id() != null && site.url() != null && site.url().startsWith("https://")
                        && site.scopes() != null && site.scopes().contains(WRITE_SCOPE))
                .map(site -> new Site(site.id(), site.name() == null ? site.url() : site.name(), site.url()))
                .toList();
    }

    private static Site site(List<Site> sites, String cloudId) {
        return sites.stream().filter(site -> site.cloudId().equals(cloudId)).findFirst()
                .orElseThrow(() -> new ApiExceptions.BadRequest("That isn't a Jira site this connection reaches"));
    }

    static ApiExceptions.Conflict notConnected() {
        return new ApiExceptions.Conflict("Jira isn't connected to this organization; an organization admin can "
                + "connect it under Organization → Integrations");
    }

    static String hash(String state) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
