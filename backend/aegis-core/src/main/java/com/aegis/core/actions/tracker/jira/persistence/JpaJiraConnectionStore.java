package com.aegis.core.actions.tracker.jira.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.aegis.core.actions.tracker.jira.JiraConnectionStore;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.tenant.CurrentOrg;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA adapter for {@link JiraConnectionStore}. Every method resolves the caller's org through {@link #scope()}
 * — which also binds it on the database session for the row-level-security backstop (V24) — and reads or
 * writes only that org's rows.
 */
@Repository
class JpaJiraConnectionStore implements JiraConnectionStore {

    @PersistenceContext
    private EntityManager entityManager;

    private final CurrentOrg currentOrg;
    private final JiraConnectionJpaRepository connections;
    private final JiraConnectStateJpaRepository states;

    JpaJiraConnectionStore(CurrentOrg currentOrg, JiraConnectionJpaRepository connections,
            JiraConnectStateJpaRepository states) {
        this.currentOrg = currentOrg;
        this.connections = connections;
        this.states = states;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Connection> find() {
        return connections.findById(scope()).map(JiraConnectionEntity::toDomain);
    }

    @Override
    @Transactional
    public Connection connect(String cloudId, String siteName, String siteUrl, SealedTokens tokens, UUID connectedBy) {
        UUID org = scope();
        JiraConnectionEntity connection = connections.lockByOrgId(org).orElseGet(() -> new JiraConnectionEntity(org));
        connection.connect(cloudId, siteName, siteUrl, tokens, connectedBy);
        return saved(connection);
    }

    @Override
    @Transactional
    public Connection configure(String cloudId, String siteName, String siteUrl, String projectKey, String issueType) {
        JiraConnectionEntity connection = connections.lockByOrgId(scope()).orElseThrow(JpaJiraConnectionStore::notConnected);
        connection.site(cloudId, siteName, siteUrl);
        connection.destination(projectKey, issueType);
        return saved(connection);
    }

    @Override
    @Transactional
    public boolean disconnect() {
        return connections.deleteByOrg(scope()) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SealedTokens> tokens() {
        return connections.findById(scope()).map(JiraConnectionEntity::tokens);
    }

    @Override
    @Transactional
    public SealedTokens renewTokens(UnaryOperator<SealedTokens> renew) {
        JiraConnectionEntity connection = connections.lockByOrgId(scope()).orElseThrow(JpaJiraConnectionStore::notConnected);
        SealedTokens current = connection.tokens();
        SealedTokens kept = renew.apply(current);
        if (!kept.equals(current)) {
            connection.tokens(kept);
            connections.saveAndFlush(connection);
        }
        return kept;
    }

    @Override
    @Transactional
    public void startConnecting(String stateHash, UUID userId, Instant expiresAt, Instant now) {
        UUID org = scope();
        states.deleteExpired(org, now);
        states.save(new JiraConnectStateEntity(stateHash, org, userId, expiresAt));
    }

    @Override
    @Transactional
    public Optional<PendingConnection> takePending(String stateHash) {
        UUID org = scope();
        Optional<JiraConnectStateEntity> pending = states.findByStateHashAndOrgId(stateHash, org);
        if (pending.isEmpty() || states.deleteState(stateHash, org) != 1) {
            return Optional.empty();
        }
        return pending.map(state -> new PendingConnection(state.userId(), state.expiresAt()));
    }

    private Connection saved(JiraConnectionEntity connection) {
        return connections.saveAndFlush(connection).toDomain();
    }

    private static ApiExceptions.Conflict notConnected() {
        return new ApiExceptions.Conflict("Jira isn't connected to this organization; an organization admin can "
                + "connect it under Organization → Integrations");
    }

    private UUID scope() {
        UUID org = currentOrg.require();
        entityManager.createNativeQuery("select set_config('aegis.current_org', :org, true)")
                .setParameter("org", org.toString())
                .getSingleResult();
        return org;
    }
}
