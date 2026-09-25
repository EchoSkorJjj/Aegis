package com.aegis.core.actions.pr.github;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.aegis.core.platform.common.ApiExceptions;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpMethod;

final class GitHubInstallationTokens {
    private final GitHubApi api;
    private final GitHubAppJwt jwt;
    private final Set<String> allowedAccounts;
    private final Clock clock;
    private final Map<CacheKey, Token> cache = new HashMap<>();

    GitHubInstallationTokens(GitHubApi api, GitHubAppJwt jwt, Set<String> allowedAccounts, Clock clock) {
        this.api = api;
        this.jwt = jwt;
        this.allowedAccounts = allowedAccounts;
        this.clock = clock;
    }

    synchronized String forRepository(String repository) {
        if (allowedAccounts.isEmpty()) {
            throw new ApiExceptions.Forbidden("GitHub operations are disabled until allowed-accounts is configured");
        }
        String appJwt = jwt.create();
        var installation = api.call(HttpMethod.GET, api.uri("repos/" + repository + "/installation", null),
                appJwt, null, Installation.class, false,
                "the Aegis GitHub App is not installed on " + repository);
        if (installation.id() <= 0 || installation.account() == null || installation.account().login() == null) {
            throw GitHubApi.invalidResponse();
        }
        if (!allowedAccounts.contains(installation.account().login().toLowerCase(Locale.ROOT))) {
            throw new ApiExceptions.Forbidden("GitHub installation account is not in allowed-accounts");
        }
        // Lookup and account validation occur even on cache hits (installation access can change).
        var now = clock.instant();
        cache.values().removeIf(value -> !value.expiresAt().isAfter(now));
        String repo = repository.split("/")[1];
        var key = new CacheKey(installation.id(), repo.toLowerCase(Locale.ROOT));
        Token token = cache.get(key);
        if (token == null || token.expiresAt().isBefore(now.plusSeconds(5 * 60))) {
            token = api.post("app/installations/" + installation.id() + "/access_tokens", appJwt,
                    Map.of("repositories", List.of(repo),
                           "permissions", Map.of("contents", "write", "pull_requests", "write")), Token.class);
            if (token.token() == null || !token.token().matches("[\\x21-\\x7e]+") || token.expiresAt() == null
                    || !token.expiresAt().isAfter(now.plusSeconds(5 * 60))) {
                throw GitHubApi.invalidResponse();
            }
            cache.put(key, token);
        }
        return token.token();
    }

    private record CacheKey(long installation, String repository) { }

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
