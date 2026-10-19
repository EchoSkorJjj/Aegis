package com.aegis.core.notifications;

import java.util.List;
import java.util.UUID;

import com.aegis.core.platform.identity.IdentityInternalApi;
import com.aegis.core.platform.scheduling.SchedulerLease;
import com.aegis.core.platform.tenant.CurrentOrg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the owner notifications ({@link OwnerNotifier}) in every organization, on the instance holding the
 * {@link SchedulerLease}. The organizations come from identity-service, the one place that knows them all: the
 * inventory's own tables are row-level secured, so no query here could list them. On only with
 * {@code aegis.notifications.enabled}, which also needs identity-service's service token.
 */
@Component
@ConditionalOnProperty(prefix = "aegis.notifications", name = "enabled", havingValue = "true")
class OwnerNotificationJob {

    private static final Logger log = LoggerFactory.getLogger(OwnerNotificationJob.class);

    private final OwnerNotifier notifier;
    private final IdentityInternalApi identity;
    private final CurrentOrg currentOrg;
    private final SchedulerLease lease;

    OwnerNotificationJob(OwnerNotifier notifier, IdentityInternalApi identity, CurrentOrg currentOrg,
            SchedulerLease lease) {
        this.notifier = notifier;
        this.identity = identity;
        this.currentOrg = currentOrg;
        this.lease = lease;
    }

    @Scheduled(
            fixedDelayString = "${aegis.notifications.interval:PT5M}",
            initialDelayString = "${aegis.notifications.initial-delay:PT1M}")
    void run() {
        if (!lease.held()) {
            return;
        }
        List<UUID> organizations;
        try {
            organizations = identity.organizationIds();
        } catch (RuntimeException unavailable) {
            log.warn("owner notifications: could not list the organizations ({}); trying again next run",
                    unavailable.getMessage());
            return;
        }
        for (UUID org : organizations) {
            try {
                currentOrg.runAs(org, () -> {
                    OwnerNotifier.Run run = notifier.notifyOwners();
                    if (run.notified() > 0 || run.emailsSent() > 0) {
                        log.info("owner notifications: org {}: {} new, {} email(s) sent", org, run.notified(),
                                run.emailsSent());
                    }
                });
            } catch (RuntimeException failed) {
                // One organization's data must never stop the others being notified.
                log.warn("owner notifications: org {} skipped this run ({})", org, failed.toString());
            }
        }
    }
}
