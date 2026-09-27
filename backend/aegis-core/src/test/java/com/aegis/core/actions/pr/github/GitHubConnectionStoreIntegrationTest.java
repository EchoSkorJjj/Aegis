package com.aegis.core.actions.pr.github;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.aegis.core.actions.pr.github.GitHubConnectionStore.Connection;
import com.aegis.core.platform.tenant.CurrentOrg;
import com.aegis.core.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * The connections each organization made (V22) stay that organization's: another one neither sees them nor
 * is covered by them, and a connection in progress completes only in the organization that started it.
 * Every test works in fresh organizations, so the shared database never matters.
 */
class GitHubConnectionStoreIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private GitHubConnectionStore store;

    @Autowired
    private CurrentOrg currentOrg;

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    @Test
    void aConnectionCoversOnlyTheOrganizationThatMadeIt() {
        Connection made = as(orgA, () -> store.connect(42, "octo", 1001, "User", admin));
        assertThat(made.connectedAt()).as("the database's clock").isNotNull();

        assertThat(as(orgA, store::findAll)).extracting(Connection::installationId, Connection::accountLogin,
                Connection::connectedBy).containsExactly(tuple(42L, "octo", admin));
        assertThat(as(orgA, () -> store.isConnected(42))).isTrue();
        assertThat(as(orgB, store::findAll)).isEmpty();
        assertThat(as(orgB, () -> store.isConnected(42))).isFalse();
        assertThat(as(orgB, store::hasAny)).isFalse();

        // Each organization's admin can connect the same installation; disconnecting is one organization's own.
        as(orgB, () -> store.connect(42, "octo", 1001, "User", UUID.randomUUID()));
        assertThat(as(orgA, () -> store.disconnect(42))).isTrue();
        assertThat(as(orgA, () -> store.disconnect(42))).isFalse();
        assertThat(as(orgB, () -> store.isConnected(42))).isTrue();
    }

    @Test
    void connectingAgainRefreshesTheAccountButKeepsWhoConnectedIt() {
        as(orgA, () -> store.connect(7, "old-login", 77, "Organization", admin));
        Connection again = as(orgA, () -> store.connect(7, "new-login", 77, "Organization", UUID.randomUUID()));

        assertThat(again.accountLogin()).isEqualTo("new-login");
        assertThat(again.connectedBy()).isEqualTo(admin);
        assertThat(as(orgA, store::findAll)).singleElement().extracting(Connection::accountLogin).isEqualTo("new-login");
    }

    @Test
    void aConnectionInProgressCompletesOnceAndOnlyInItsOrganization() {
        Instant now = Instant.now();
        as(orgA, () -> {
            store.startConnecting("a".repeat(64), admin, now.plusSeconds(600), now);
            return null;
        });

        assertThat(as(orgB, () -> store.takePending("a".repeat(64)))).isEmpty();
        assertThat(as(orgA, () -> store.takePending("a".repeat(64)))).get()
                .satisfies(pending -> assertThat(pending.userId()).isEqualTo(admin));
        assertThat(as(orgA, () -> store.takePending("a".repeat(64)))).as("used up").isEmpty();
    }

    @Test
    void startingAConnectionClearsTheOrganizationsExpiredOnes() {
        Instant now = Instant.now();
        String expired = UUID.randomUUID().toString().replace("-", "") + "e".repeat(32);
        String fresh = UUID.randomUUID().toString().replace("-", "") + "f".repeat(32);
        as(orgA, () -> {
            store.startConnecting(expired, admin, now.minusSeconds(1), now.minusSeconds(601));
            store.startConnecting(fresh, admin, now.plusSeconds(600), now);
            return null;
        });

        assertThat(jdbc.queryForObject("select count(*) from core.github_connect_states where state_hash in (?, ?)",
                Integer.class, expired, fresh)).isEqualTo(1);
        assertThat(as(orgA, () -> store.takePending(fresh))).isPresent();
    }

    private <T> T as(UUID org, Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        currentOrg.runAs(org, () -> result.set(action.get()));
        return result.get();
    }
}
