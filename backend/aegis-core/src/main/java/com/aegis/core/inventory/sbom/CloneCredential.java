package com.aegis.core.inventory.sbom;

import java.util.Locale;
import java.util.regex.Pattern;

import com.aegis.core.platform.common.ApiExceptions;

/**
 * What git sends to clone one repository over HTTPS: a non-secret {@code username}, which goes in the clone
 * URL, and a {@code secret}, which reaches git only through {@code GIT_ASKPASS} — never argv, the URL or
 * {@code .git/config}.
 */
public record CloneCredential(String username, String secret) {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._~-]{1,100}");
    private static final Pattern SECRET = Pattern.compile("[\\x21-\\x7e]{1,500}");

    public CloneCredential {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new ApiExceptions.BadRequest("That username is not allowed");
        }
        if (secret == null || !SECRET.matcher(secret).matches()) {
            throw new ApiExceptions.BadRequest("An access token must be printable characters without spaces");
        }
    }

    /**
     * A pasted access token, sent with {@code username} when one is given and otherwise with the name the
     * host expects: GitHub ignores it, GitLab takes {@code oauth2}, and Bitbucket's two token kinds each have
     * a fixed one — access tokens ({@code ATCTT…}) {@code x-token-auth}, API tokens ({@code ATATT…})
     * {@code x-bitbucket-api-token-auth}. Any other host gets {@code x-access-token}; a host that wants the
     * account's own name (Gitea, Bitbucket Data Center personal tokens) needs {@code username}.
     */
    public static CloneCredential forToken(String host, String token, String username) {
        String secret = token == null ? null : token.trim();
        if (username != null && !username.isBlank()) {
            return new CloneCredential(username.trim(), secret);
        }
        String lower = host.toLowerCase(Locale.ROOT);
        String conventional;
        if (lower.contains("bitbucket")) {
            conventional = secret != null && secret.startsWith("ATATT") ? "x-bitbucket-api-token-auth" : "x-token-auth";
        } else if (lower.contains("gitlab")) {
            conventional = "oauth2";
        } else {
            conventional = "x-access-token";
        }
        return new CloneCredential(conventional, secret);
    }

    @Override
    public String toString() {
        return "CloneCredential[username=" + username + ", secret=***]";
    }
}
