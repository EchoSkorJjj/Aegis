package com.aegis.core.actions.triage;

import java.time.Instant;

import com.aegis.core.actions.memo.RegulatorMemoStore;
import com.aegis.core.actions.ticket.RiskTicketStore;
import com.aegis.core.actions.upgrade.UpgradeProposalStore;
import com.aegis.core.platform.identity.IdentityInternalApi;
import com.aegis.core.platform.scheduling.SchedulerLease;
import com.aegis.core.platform.tenant.CurrentOrg;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Aggregate Review & approve depth; no tenant identifiers, response text or database work at scrape time. */
@Component
@ConditionalOnProperty(prefix = "aegis.metrics.decision-queue", name = "enabled", havingValue = "true")
class DecisionQueueMetrics {

    private static final Logger log = LoggerFactory.getLogger(DecisionQueueMetrics.class);
    private final RiskTicketStore tickets;
    private final UpgradeProposalStore upgrades;
    private final RegulatorMemoStore memos;
    private final IdentityInternalApi identity;
    private final CurrentOrg currentOrg;
    private final SchedulerLease lease;
    private volatile Snapshot snapshot = Snapshot.UNKNOWN;

    DecisionQueueMetrics(RiskTicketStore tickets, UpgradeProposalStore upgrades, RegulatorMemoStore memos,
            IdentityInternalApi identity, CurrentOrg currentOrg, SchedulerLease lease, MeterRegistry registry) {
        this.tickets = tickets;
        this.upgrades = upgrades;
        this.memos = memos;
        this.identity = identity;
        this.currentOrg = currentOrg;
        this.lease = lease;
        Gauge.builder("aegis.decision.queue.depth", this, metrics -> metrics.snapshot.depth())
                .description("Responses awaiting attention across all organizations, as in Review & approve")
                .register(registry);
        Gauge.builder("aegis.decision.queue.last.refresh.timestamp.seconds", this,
                        metrics -> metrics.snapshot.refreshedAt())
                .description("Unix timestamp of the last complete decision queue count; 0 means unknown")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${aegis.metrics.decision-queue.interval:PT1M}",
            initialDelayString = "${aegis.metrics.decision-queue.interval:PT1M}")
    void refresh() {
        if (!lease.held()) {
            snapshot = Snapshot.UNKNOWN;
            return;
        }
        try {
            long[] total = {0};
            for (var org : identity.organizationIds()) {
                currentOrg.runAs(org, () -> total[0] += tickets.countPending()
                        + upgrades.countPendingStandalone() + memos.countPending());
            }
            // Publish only a complete pass: a failed organization must never look like an empty queue.
            snapshot = new Snapshot(total[0], Instant.now().toEpochMilli() / 1000.0);
        } catch (RuntimeException unavailable) {
            snapshot = Snapshot.UNKNOWN;
            log.warn("decision queue metrics: refresh failed; depth is unknown ({})",
                    unavailable.getClass().getSimpleName());
        }
    }

    private record Snapshot(double depth, double refreshedAt) {
        private static final Snapshot UNKNOWN = new Snapshot(Double.NaN, 0);
    }
}
