package com.aegis.core.actions.tracker.jira;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Each organization's Jira connection (V24) — one site, and the project and issue type its risk tickets
 * are filed as — and connections in progress. Every method is scoped to the caller's organization. Tokens
 * are only ever handed in and out sealed ({@code SecretBox}); this store never sees them in the clear.
 */
public interface JiraConnectionStore {

    /** The organization's Jira site, where its tickets go (null until chosen), and who connected it when. */
    record Connection(String cloudId, String siteName, String siteUrl, String projectKey, String issueType,
                      UUID connectedBy, Instant connectedAt) {

        public boolean configured() {
            return projectKey != null && issueType != null;
        }
    }

    /** The connection's tokens, sealed. Atlassian rotates the refresh token on every renewal. */
    record SealedTokens(String accessToken, Instant accessExpiresAt, String refreshToken) {
    }

    /** Who started a connection that has not completed yet, and until when it may complete. */
    record PendingConnection(UUID userId, Instant expiresAt) {
    }

    Optional<Connection> find();

    /** Connects (or reconnects) the organization's Jira site; where tickets go is chosen afterwards. */
    Connection connect(String cloudId, String siteName, String siteUrl, SealedTokens tokens, UUID connectedBy);

    /** Where the organization's tickets go: a site the connection reaches, a project there, an issue type. */
    Connection configure(String cloudId, String siteName, String siteUrl, String projectKey, String issueType);

    /** False when Jira was not connected. */
    boolean disconnect();

    Optional<SealedTokens> tokens();

    /**
     * Renews the tokens while holding the connection's row lock: {@code renew} sees the tokens as stored now
     * (another instance may have renewed them a moment ago) and returns the ones to keep — the same, or new.
     * {@code ApiExceptions.Conflict} when the organization has not connected Jira.
     */
    SealedTokens renewTokens(UnaryOperator<SealedTokens> renew);

    void startConnecting(String stateHash, UUID userId, Instant expiresAt, Instant now);

    /** The connection in progress under this state hash, removed so it can complete only once. */
    Optional<PendingConnection> takePending(String stateHash);
}
