package com.aegis.core.actions.tracker.jira;

import java.net.URI;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The Atlassian OAuth 2.0 (3LO) app Aegis files risk tickets through. Each organization's admin connects
 * their own Jira site with it; GitHub-style, no organization pastes a credential. {@code callbackUrl} must
 * equal the app's registered callback: the frontend page Atlassian sends the admin back to.
 */
@ConfigurationProperties("aegis.actions.jira")
record JiraProperties(
        @DefaultValue("false") boolean enabled,
        String clientId,
        String clientSecret,
        @DefaultValue("http://localhost:3000/admin/integrations/jira/callback") URI callbackUrl,
        @DefaultValue("https://auth.atlassian.com") URI authBaseUrl,
        @DefaultValue("https://api.atlassian.com") URI apiBaseUrl) {

    /** What Aegis asks for: create issues and read projects, and a refresh token to keep doing it. */
    static final String SCOPES = "read:jira-work write:jira-work offline_access";

    void validate() {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("Jira client-id is required when enabled");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException("Jira client-secret is required when enabled");
        }
        if (!safeOrigin(authBaseUrl) || !safeOrigin(apiBaseUrl)) {
            throw new IllegalStateException("Atlassian URLs must use HTTPS (HTTP loopback is allowed for tests)");
        }
        if (callbackUrl == null || callbackUrl.getHost() == null || callbackUrl.getUserInfo() != null
                || callbackUrl.getFragment() != null
                || !("https".equals(callbackUrl.getScheme()) || "http".equals(callbackUrl.getScheme()))) {
            throw new IllegalStateException("Jira callback URL must be an absolute http(s) URL");
        }
    }

    /** Never prints the client secret, whoever logs this. */
    @Override
    public String toString() {
        return "JiraProperties[enabled=" + enabled + ", clientId=" + clientId + ", callbackUrl=" + callbackUrl
                + ", authBaseUrl=" + authBaseUrl + ", apiBaseUrl=" + apiBaseUrl + "]";
    }

    private static boolean safeOrigin(URI uri) {
        return uri != null && uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null
                && uri.getFragment() == null
                && ("https".equals(uri.getScheme())
                    || ("http".equals(uri.getScheme())
                        && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())));
    }
}
