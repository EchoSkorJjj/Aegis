package com.aegis.core.actions.pr.github;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The GitHub App. The client ID and private key authenticate Aegis as the App; the client secret is only for
 * an organization admin's sign-in when they connect an installation ({@link GitHubConnections}), whose
 * GitHub redirect comes back to {@code callbackUrl} — the App's registered callback URL.
 */
@ConfigurationProperties("aegis.actions.github")
record GitHubProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("https://api.github.com") URI apiBaseUrl,
        @DefaultValue("https://github.com") URI webBaseUrl,
        String clientId,
        String clientSecret,
        Path privateKeyPath,
        @DefaultValue("http://localhost:3000/admin/integrations/github/callback") URI callbackUrl,
        @DefaultValue("github.com") Set<String> hosts) {

    GitHubProperties {
        hosts = normalized(hosts);
    }

    void validate() {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("GitHub App client-id is required when enabled");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException("GitHub App client-secret is required when enabled");
        }
        if (!safeOrigin(apiBaseUrl)) {
            throw new IllegalStateException("GitHub API base URL must use HTTPS (HTTP loopback is allowed for tests)");
        }
        if (!safeOrigin(webBaseUrl)) {
            throw new IllegalStateException("GitHub web base URL must use HTTPS (HTTP loopback is allowed for tests)");
        }
        if (callbackUrl == null || callbackUrl.getHost() == null || callbackUrl.getUserInfo() != null
                || callbackUrl.getFragment() != null
                || !("https".equals(callbackUrl.getScheme()) || "http".equals(callbackUrl.getScheme()))) {
            throw new IllegalStateException("GitHub App callback URL must be an absolute http(s) URL");
        }
        if (hosts.isEmpty()) {
            throw new IllegalStateException("GitHub repository hosts must not be empty");
        }
    }

    /** Never prints the client secret, whoever logs this. */
    @Override
    public String toString() {
        return "GitHubProperties[enabled=" + enabled + ", apiBaseUrl=" + apiBaseUrl + ", webBaseUrl=" + webBaseUrl
                + ", clientId=" + clientId + ", callbackUrl=" + callbackUrl + ", hosts=" + hosts + "]";
    }

    private static boolean safeOrigin(URI uri) {
        return uri != null && uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null
                && uri.getFragment() == null
                && ("https".equals(uri.getScheme())
                    || ("http".equals(uri.getScheme())
                        && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())));
    }

    private static Set<String> normalized(Set<String> values) {
        return values == null ? Set.of() : values.stream().map(String::trim)
                .filter(value -> !value.isEmpty()).map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
