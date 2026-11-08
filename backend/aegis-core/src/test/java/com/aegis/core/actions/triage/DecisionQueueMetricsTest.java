package com.aegis.core.actions.triage;

import java.util.List;
import java.util.UUID;

import com.aegis.core.actions.memo.RegulatorMemoStore;
import com.aegis.core.actions.ticket.RiskTicketStore;
import com.aegis.core.actions.upgrade.UpgradeProposalStore;
import com.aegis.core.platform.identity.IdentityInternalApi;
import com.aegis.core.platform.scheduling.SchedulerLease;
import com.aegis.core.platform.tenant.CurrentOrg;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DecisionQueueMetricsTest {
    private final RiskTicketStore tickets = mock(RiskTicketStore.class);
    private final UpgradeProposalStore upgrades = mock(UpgradeProposalStore.class);
    private final RegulatorMemoStore memos = mock(RegulatorMemoStore.class);
    private final IdentityInternalApi identity = mock(IdentityInternalApi.class);
    private final SchedulerLease lease = mock(SchedulerLease.class);
    private final CurrentOrg org = new CurrentOrg();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DecisionQueueMetrics metrics = new DecisionQueueMetrics(
            tickets, upgrades, memos, identity, org, lease, registry);

    @Test
    void countsEveryOrganizationInItsOwnScopeWithoutTenantLabels() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(lease.held()).thenReturn(true);
        when(identity.organizationIds()).thenReturn(List.of(first, second));
        when(tickets.countPending()).thenAnswer(call -> org.require().equals(first) ? 2L : 3L);
        when(upgrades.countPendingStandalone()).thenAnswer(call -> {
            assertThat(org.require()).isIn(first, second);
            return 1L;
        });
        when(memos.countPending()).thenReturn(4L);

        assertThat(depth()).isNaN();
        metrics.refresh();

        assertThat(depth()).isEqualTo(15);
        assertThat(refreshedAt()).isPositive();
        assertThat(org.orgId()).isEmpty();
        registry.getMeters().forEach(meter -> assertThat(meter.getId().getTags()).isEmpty());
    }

    @Test
    void failedRefreshPublishesUnknownInsteadOfAPartialOrStaleTotal() {
        when(lease.held()).thenReturn(true);
        when(identity.organizationIds()).thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));
        when(tickets.countPending()).thenReturn(5L);
        metrics.refresh();
        assertThat(depth()).isEqualTo(10);

        when(tickets.countPending()).thenReturn(7L).thenThrow(new IllegalStateException("unavailable"));
        metrics.refresh();

        assertThat(depth()).isNaN();
        assertThat(refreshedAt()).isZero();
        assertThat(org.orgId()).isEmpty();
    }

    @Test
    void emptyOrganizationListIsAKnownEmptyQueue() {
        when(lease.held()).thenReturn(true);
        when(identity.organizationIds()).thenReturn(List.of());
        metrics.refresh();
        assertThat(depth()).isZero();
        assertThat(refreshedAt()).isPositive();
        verifyNoInteractions(tickets, upgrades, memos);
    }

    @Test
    void losingTheLeaseClearsTheOldTotalAndDoesNoWork() {
        when(lease.held()).thenReturn(true, false);
        when(identity.organizationIds()).thenReturn(List.of());
        metrics.refresh();
        assertThat(depth()).isZero();
        metrics.refresh();
        assertThat(depth()).isNaN();
        assertThat(refreshedAt()).isZero();
        verifyNoInteractions(tickets, upgrades, memos);
    }

    private double depth() {
        return registry.get("aegis.decision.queue.depth").gauge().value();
    }

    private double refreshedAt() {
        return registry.get("aegis.decision.queue.last.refresh.timestamp.seconds").gauge().value();
    }
}
