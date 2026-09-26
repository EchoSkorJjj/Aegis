package com.aegis.core.actions.pr.github;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;

import com.aegis.core.platform.common.ApiExceptions;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpMethod;

/**
 * Installation access tokens, each narrowed to one repository and one {@link Access}: a draft pull
 * request's write access is never handed to a read, and a read never gets more than the contents. Only an
 * installation the caller's organization has connected ({@link GitHubConnections}) is used at all.
 */
final class GitHubInstallationTokens {

    /** What a token may do in the one repository it is minted for. */
    enum Access {
        /** Branch, commit and draft pull request (actions.upgrade). */
        PULL_REQUEST(Map.of("contents", "write", "pull_requests", "write")),
        /** Clone only (the inventory's SBOM import). */
        READ(Map.of("contents", "read"));

        private final Map<String, String> permissions;

        Access(Map<String, String> permissions) {
            this.permissions = permissions;
        }
    }

    static final String CONNECT_HINT = "an organization admin can connect it under Organization → Integrations";

    private final GitHubApi api;
    private final GitHubAppJwt jwt;
    private final BooleanSupplier anyConnected;
    private final LongPredicate connected;
    private final Clock clock;
    private final Map<CacheKey, Token> cache = new ConcurrentHashMap<>();

    /**
     * @param anyConnected whether the caller's organization has connected any installation
     * @param connected    whether it has connected this installation
     */
    GitHubInstallationTokens(GitHubApi api, GitHubAppJwt jwt, BooleanSupplier anyConnected, LongPredicate connected,
            Clock clock) {
        this.api = api;
        this.jwt = jwt;
        this.anyConnected = anyConnected;
        this.connected = connected;
        this.clock = clock;
    }

    /**
     * Not synchronized: the installation lookup and the token mint are network calls, and one lock held
     * across them would queue every GitHub operation behind the slowest. Only the cache is shared, and
     * it is a concurrent map. Two concurrent misses for one repository may both mint a token; that is
     * harmless, since both are valid and the last one stored is reused.
     */
    String forRepository(String repository, Access access) {
        if (!anyConnected.getAsBoolean()) {
            throw new ApiExceptions.Forbidden("GitHub isn't connected to this organization; " + CONNECT_HINT);
        }
        String appJwt = jwt.create();
        var installation = api.call(HttpMethod.GET, api.uri("repos/" + repository + "/installation", null),
                appJwt, null, Installation.class, false,
                "the Aegis GitHub App is not installed on " + repository);
        if (installation.id() <= 0 || installation.account() == null || installation.account().login() == null) {
            throw GitHubApi.invalidResponse();
        }
        if (!connected.test(installation.id())) {
            throw new ApiExceptions.Forbidden("The Aegis GitHub App on " + installation.account().login()
                    + " isn't connected to this organization; " + CONNECT_HINT);
        }
        // Lookup and connection check occur even on cache hits (installation access can change).
        var now = clock.instant();
        cache.values().removeIf(value -> !value.expiresAt().isAfter(now));
        String repo = repository.split("/")[1];
        var key = new CacheKey(installation.id(), repo.toLowerCase(Locale.ROOT), access);
        Token token = cache.get(key);
        if (token == null || token.expiresAt().isBefore(now.plusSeconds(5 * 60))) {
            token = api.post("app/installations/" + installation.id() + "/access_tokens", appJwt,
                    Map.of("repositories", List.of(repo), "permissions", access.permissions), Token.class);
            if (token.token() == null || !token.token().matches("[\\x21-\\x7e]+") || token.expiresAt() == null
                    || !token.expiresAt().isAfter(now.plusSeconds(5 * 60))) {
                throw GitHubApi.invalidResponse();
            }
            cache.put(key, token);
        }
        return token.token();
    }

    private record CacheKey(long installation, String repository, Access access) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Installation(long id, Account account) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Account(String login) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Token(String token, @JsonProperty("expires_at") Instant expiresAt) {
        @Override
        public String toString() {
            return "GitHub installation token [redacted]";
        }
    }
}
