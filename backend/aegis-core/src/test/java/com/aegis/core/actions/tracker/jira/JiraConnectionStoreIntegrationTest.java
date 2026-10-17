package com.aegis.core.actions.tracker.jira;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.aegis.core.actions.tracker.jira.JiraConnectionStore.SealedTokens;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.tenant.CurrentOrg;
import com.aegis.core.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Each organization's Jira connection (V24) is its own: another organization neither sees it nor renews its
 * tokens, and a connection in progress completes only in the organization that started it. Every test works
 * in fresh organizations.
 */
class JiraConnectionStoreIntegrationTest extends AbstractIntegrationTest {

    private static final SealedTokens FIRST = new SealedTokens("sealed-access-1", Instant.parse("2099-01-01T00:00:00Z"),
            "sealed-refresh-1");

    @Autowired
    private JiraConnectionStore store;

    @Autowired
    private CurrentOrg currentOrg;

    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    @Test
    void aConnectionIsItsOrganizationsAloneAndChoosingAProjectKeepsTheTokens() {
        as(orgA, () -> store.connect("cloud-a", "Acme", "https://acme.atlassian.net", FIRST, admin));

        assertThat(as(orgA, store::find)).get().satisfies(connection -> {
            assertThat(connection.siteName()).isEqualTo("Acme");
            assertThat(connection.configured()).isFalse();
            assertThat(connection.connectedAt()).isNotNull();
        });
        assertThat(as(orgB, store::find)).isEmpty();
        assertThatThrownBy(() -> as(orgB, () -> store.renewTokens(tokens -> tokens)))
                .isInstanceOf(ApiExceptions.Conflict.class);

        assertThat(as(orgA, () -> store.configure("cloud-a", "Acme", "https://acme.atlassian.net", "SEC", "Task")).configured())
                .isTrue();
        assertThat(as(orgA, store::tokens)).contains(FIRST);
        assertThat(as(orgA, store::disconnect)).isTrue();
        assertThat(as(orgA, store::disconnect)).isFalse();
    }

    @Test
    void renewingSeesTheStoredTokensAndKeepsWhatItReturns() {
        as(orgA, () -> store.connect("cloud-a", "Acme", "https://acme.atlassian.net", FIRST, admin));
        SealedTokens second = new SealedTokens("sealed-access-2", Instant.parse("2099-01-02T00:00:00Z"), "sealed-refresh-2");

        SealedTokens kept = as(orgA, () -> store.renewTokens(current -> {
            assertThat(current).isEqualTo(FIRST);
            return second;
        }));

        assertThat(kept).isEqualTo(second);
        assertThat(as(orgA, store::tokens)).contains(second);
    }

    @Test
    void reconnectingStartsTheDestinationAfresh() {
        as(orgA, () -> store.connect("cloud-a", "Acme", "https://acme.atlassian.net", FIRST, admin));
        as(orgA, () -> store.configure("cloud-a", "Acme", "https://acme.atlassian.net", "SEC", "Task"));
        UUID otherAdmin = UUID.randomUUID();

        var again = as(orgA, () -> store.connect("cloud-b", "Beta", "https://beta.atlassian.net", FIRST, otherAdmin));

        assertThat(again.cloudId()).isEqualTo("cloud-b");
        assertThat(again.configured()).isFalse();
        assertThat(again.connectedBy()).isEqualTo(otherAdmin);
    }

    @Test
    void aConnectionInProgressCompletesOnceAndOnlyInItsOrganization() {
        Instant now = Instant.now();
        String hash = UUID.randomUUID().toString().replace("-", "") + "0".repeat(32);
        as(orgA, () -> {
            store.startConnecting(hash, admin, now.plusSeconds(600), now);
            return null;
        });

        assertThat(as(orgB, () -> store.takePending(hash))).isEmpty();
        assertThat(as(orgA, () -> store.takePending(hash))).get()
                .satisfies(pending -> assertThat(pending.userId()).isEqualTo(admin));
        assertThat(as(orgA, () -> store.takePending(hash))).isEmpty();
    }

    private <T> T as(UUID org, Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        currentOrg.runAs(org, () -> result.set(action.get()));
        return result.get();
    }
}
