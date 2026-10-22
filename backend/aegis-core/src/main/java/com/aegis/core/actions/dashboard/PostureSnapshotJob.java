package com.aegis.core.actions.dashboard;

import com.aegis.core.platform.identity.IdentityInternalApi;
import com.aegis.core.platform.scheduling.SchedulerLease;
import com.aegis.core.platform.tenant.CurrentOrg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Captures each organization's latest daily observation even when no one opens its dashboard. */
@Component
@ConditionalOnProperty(prefix = "aegis.dashboard.snapshots", name = "enabled", havingValue = "true")
class PostureSnapshotJob {
    private static final Logger log = LoggerFactory.getLogger(PostureSnapshotJob.class);
    private final RiskPosture posture;
    private final IdentityInternalApi identity;
    private final CurrentOrg currentOrg;
    private final SchedulerLease lease;

    PostureSnapshotJob(RiskPosture posture, IdentityInternalApi identity, CurrentOrg currentOrg,
            SchedulerLease lease) {
        this.posture = posture;
        this.identity = identity;
        this.currentOrg = currentOrg;
        this.lease = lease;
    }

    @Scheduled(fixedDelayString = "${aegis.dashboard.snapshots.interval:PT1H}",
            initialDelayString = "${aegis.dashboard.snapshots.initial-delay:PT2M}")
    void run() {
        if (!lease.held()) {
            return;
        }
        try {
            for (var org : identity.organizationIds()) {
                try {
                    currentOrg.runAs(org, posture::capture);
                } catch (RuntimeException failed) {
                    log.warn("risk posture: org {} not captured ({})", org, failed.toString());
                }
            }
        } catch (RuntimeException unavailable) {
            log.warn("risk posture: could not list organizations ({})", unavailable.toString());
        }
    }
}
