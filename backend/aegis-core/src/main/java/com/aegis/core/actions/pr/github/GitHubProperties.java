package com.aegis.core.actions.pr.github;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("aegis.actions.github")
record GitHubProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("https://api.github.com") URI apiBaseUrl,
        String clientId,
        Path privateKeyPath,
        @DefaultValue("github.com") Set<String> hosts,
        Set<String> allowedAccounts) {

    GitHubProperties {
        hosts = normalized(hosts);
        allowedAccounts = normalized(allowedAccounts);
    }

    void validate() {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("GitHub App client-id is required when enabled");
        }
        if (apiBaseUrl == null || apiBaseUrl.getHost() == null || apiBaseUrl.getUserInfo() != null
                || apiBaseUrl.getQuery() != null || apiBaseUrl.getFragment() != null
                || !("https".equals(apiBaseUrl.getScheme())
                    || ("http".equals(apiBaseUrl.getScheme())
                        && Set.of("localhost", "127.0.0.1", "[::1]").contains(apiBaseUrl.getHost())))) {
            throw new IllegalStateException("GitHub API base URL must use HTTPS (HTTP loopback is allowed for tests)");
        }
        if (hosts.isEmpty()) {
            throw new IllegalStateException("GitHub repository hosts must not be empty");
        }
    }

    private static Set<String> normalized(Set<String> values) {
        return values == null ? Set.of() : values.stream().map(String::trim)
                .filter(value -> !value.isEmpty()).map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
