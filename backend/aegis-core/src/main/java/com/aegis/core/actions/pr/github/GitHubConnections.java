package com.aegis.core.actions.pr.github;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.aegis.core.actions.pr.github.GitHubConnectionStore.Connection;
import com.aegis.core.actions.pr.github.GitHubConnectionStore.PendingConnection;
import com.aegis.core.actions.pr.github.GitHubUserAuthorization.Account;
import com.aegis.core.actions.pr.github.GitHubUserAuthorization.Installation;
import com.aegis.core.platform.common.ApiExceptions;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Connect GitHub: which of the App's installations an organization may act through (V22). GitHub knows
 * that the App is installed on an account, never which Aegis organization it is for — so an organization
 * admin connects it. {@link #start} sends them to sign in to GitHub with a one-time state bound to them and
 * their organization; {@link #complete}, when GitHub sends them back, connects each installation they
 * <em>control</em>: one on their own account, or on a GitHub organization they administer. Seeing an
 * installation is not enough — any member of a GitHub organization can see its installations, and
 * connecting one hands Aegis write access to its repositories.
 */
final class GitHubConnections {

    /** How long a started connection may take to come back from GitHub. */
    static final Duration PENDING_FOR = Duration.ofMinutes(10);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final GitHubProperties properties;
    private final GitHubApi api;
    private final GitHubAppJwt jwt;
    private final GitHubUserAuthorization users;
    private final GitHubConnectionStore store;
    private final Clock clock;
    private volatile String installUrl;

    GitHubConnections(GitHubProperties properties, GitHubApi api, GitHubAppJwt jwt, GitHubUserAuthorization users,
            GitHubConnectionStore store, Clock clock) {
        this.properties = properties;
        this.api = api;
        this.jwt = jwt;
        this.users = users;
        this.store = store;
        this.clock = clock;
    }

    /** An account the signed-in person could see an installation on, but may not connect, and why. */
    record Skipped(String accountLogin, String reason) {
    }

    record Completed(List<Connection> connected, List<Skipped> skipped) {
    }

    List<Connection> connections() {
        return store.findAll();
    }

    /** Where to install the App on another account, or null when GitHub couldn't be asked. */
    String installUrl() {
        String url = installUrl;
        if (url == null) {
            try {
                App app = api.get("app", jwt.create(), App.class);
                if (app.slug() != null && app.slug().matches("[a-z0-9][a-z0-9-]*")) {
                    url = UriComponentsBuilder.fromUri(properties.webBaseUrl())
                            .pathSegment("apps", app.slug(), "installations", "new").build().encode().toUriString();
                    installUrl = url;
                }
            } catch (ApiExceptions.UpstreamUnavailable | ApiExceptions.NotFound unreachable) {
                return null;
            }
        }
        return url;
    }

    /** The GitHub sign-in to send {@code userId} to, carrying a state only this organization and person can complete. */
    URI start(UUID userId) {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Instant now = clock.instant();
        store.startConnecting(hash(state), userId, now.plus(PENDING_FOR), now);
        return UriComponentsBuilder.fromUri(properties.webBaseUrl()).path("/login/oauth/authorize")
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.callbackUrl().toString())
                .queryParam("state", state)
                .queryParam("allow_signup", "false")
                .build().encode().toUri();
    }

    /**
     * GitHub sent {@code userId} back with this code and state: connect every installation they control. The
     * state must be one this person started in this organization, unexpired and unused; the user token is
     * revoked whatever happens.
     */
    Completed complete(UUID userId, String code, String state) {
        Optional<PendingConnection> pending = store.takePending(hash(state));
        if (pending.isEmpty() || !pending.get().userId().equals(userId)
                || !pending.get().expiresAt().isAfter(clock.instant())) {
            throw new ApiExceptions.Forbidden(
                    "This GitHub sign-in wasn't started by you in this organization, or it expired; connect again");
        }
        String token = users.token(code);
        try {
            Account user = users.user(token);
            List<Connection> connected = new ArrayList<>();
            List<Skipped> skipped = new ArrayList<>();
            for (Installation installation : users.installations(token)) {
                Account account = installation.account();
                String refusal = refusal(token, user, account);
                if (refusal == null) {
                    connected.add(store.connect(installation.id(), account.login(), account.id(), account.type(),
                            userId));
                } else {
                    skipped.add(new Skipped(account.login(), refusal));
                }
            }
            return new Completed(connected, skipped);
        } finally {
            users.revoke(token);
        }
    }

    void disconnect(long installationId) {
        if (!store.disconnect(installationId)) {
            throw new ApiExceptions.NotFound("That GitHub installation isn't connected to this organization");
        }
    }

    /** Why the signed-in person may not connect the installation on {@code account}, or null when they may. */
    private String refusal(String token, Account user, Account account) {
        if ("User".equals(account.type())) {
            return account.id() == user.id() ? null : "It is installed on another person's GitHub account";
        }
        if ("Organization".equals(account.type())) {
            Optional<Boolean> administers = users.administers(token, account.login());
            if (administers.isEmpty()) {
                return "GitHub wouldn't say whether you administer " + account.login()
                        + ": give the App read access to organization members, then connect again";
            }
            return administers.get() ? null : "Only an admin of the " + account.login() + " GitHub organization can connect it";
        }
        return "Aegis connects installations on user and organization accounts only";
    }

    static String hash(String state) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record App(String slug) {
    }
}
