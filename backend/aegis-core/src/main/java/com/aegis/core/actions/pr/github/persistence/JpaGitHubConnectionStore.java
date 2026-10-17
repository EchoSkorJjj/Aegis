package com.aegis.core.actions.pr.github.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.aegis.core.actions.pr.github.GitHubConnectionStore;
import com.aegis.core.platform.tenant.CurrentOrg;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA adapter for {@link GitHubConnectionStore}. Every method resolves the caller's org through
 * {@link #scope()} — which also binds it on the database session for the row-level-security backstop (V22)
 * — then reads only that org's rows and stamps it on inserts.
 */
@Repository
class JpaGitHubConnectionStore implements GitHubConnectionStore {

    @PersistenceContext
    private EntityManager entityManager;

    private final CurrentOrg currentOrg;
    private final GitHubConnectionJpaRepository connections;
    private final GitHubConnectStateJpaRepository states;

    JpaGitHubConnectionStore(CurrentOrg currentOrg, GitHubConnectionJpaRepository connections,
            GitHubConnectStateJpaRepository states) {
        this.currentOrg = currentOrg;
        this.connections = connections;
        this.states = states;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Connection> findAll() {
        return connections.findByOrgIdOrderByAccountLoginAsc(scope()).stream()
                .map(GitHubConnectionEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isConnected(long installationId) {
        return connections.existsByOrgIdAndInstallationId(scope(), installationId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasAny() {
        return connections.existsByOrgId(scope());
    }

    @Override
    @Transactional
    public Connection connect(long installationId, String accountLogin, long accountId, String accountType,
            UUID connectedBy) {
        UUID org = scope();
        GitHubConnectionEntity connection = connections.findByOrgIdAndInstallationId(org, installationId)
                .orElseGet(() -> new GitHubConnectionEntity(org, installationId, connectedBy));
        connection.account(accountLogin, accountId, accountType);
        GitHubConnectionEntity saved = connections.saveAndFlush(connection);
        entityManager.refresh(saved); // connected_at is the database's
        return saved.toDomain();
    }

    @Override
    @Transactional
    public boolean disconnect(long installationId) {
        return connections.deleteConnection(scope(), installationId) > 0;
    }

    @Override
    @Transactional
    public void startConnecting(String stateHash, UUID userId, Instant expiresAt, Instant now) {
        UUID org = scope();
        states.deleteExpired(org, now);
        states.save(new GitHubConnectStateEntity(stateHash, org, userId, expiresAt));
    }

    @Override
    @Transactional
    public Optional<PendingConnection> takePending(String stateHash) {
        UUID org = scope();
        Optional<GitHubConnectStateEntity> pending = states.findByStateHashAndOrgId(stateHash, org);
        if (pending.isEmpty() || states.deleteState(stateHash, org) != 1) {
            return Optional.empty();
        }
        return pending.map(state -> new PendingConnection(state.userId(), state.expiresAt()));
    }

    private UUID scope() {
        UUID org = currentOrg.require();
        entityManager.createNativeQuery("select set_config('aegis.current_org', :org, true)")
                .setParameter("org", org.toString())
                .getSingleResult();
        return org;
    }
}
