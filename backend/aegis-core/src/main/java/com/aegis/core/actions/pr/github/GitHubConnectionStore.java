package com.aegis.core.actions.pr.github;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The GitHub App installations each organization has connected (V22), and the connections in progress.
 * Every method is scoped to the caller's organization; nothing reads or writes another organization's rows.
 */
public interface GitHubConnectionStore {

    /** One connected installation: the GitHub account it is on, and who connected it when. */
    record Connection(long installationId, String accountLogin, long accountId, String accountType,
                      UUID connectedBy, Instant connectedAt) {
    }

    /** Who started a connection that has not completed yet, and until when it may complete. */
    record PendingConnection(UUID userId, Instant expiresAt) {
    }

    List<Connection> findAll();

    boolean isConnected(long installationId);

    boolean hasAny();

    /** Connects the installation, or refreshes its account details if it already is; {@code connectedAt} is the database's. */
    Connection connect(long installationId, String accountLogin, long accountId, String accountType, UUID connectedBy);

    /** False when the installation was not connected. */
    boolean disconnect(long installationId);

    /** Records a connection in progress under the hash of its state; expired ones are cleared on the way. */
    void startConnecting(String stateHash, UUID userId, Instant expiresAt, Instant now);

    /** The connection in progress under this state hash, removed so it can complete only once. */
    Optional<PendingConnection> takePending(String stateHash);
}
