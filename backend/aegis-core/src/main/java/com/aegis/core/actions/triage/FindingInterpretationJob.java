package com.aegis.core.actions.triage;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.aegis.core.platform.identity.IdentityInternalApi;
import com.aegis.core.platform.scheduling.SchedulerLease;
import com.aegis.core.platform.tenant.CurrentOrg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Has the AI read open material findings ({@link FindingInterpretations}) on the instance holding the
 * {@link SchedulerLease}. It shares the ai-service's token budgets with people drafting responses, so it reads a few
 * findings a run and a capped number a UTC day, and stops at the first failure. The organizations are shuffled so
 * that one with a long queue can't always spend the run. On only with {@code aegis.interpretation.enabled}, which also
 * needs identity-service's service token.
 */
@Component
@ConditionalOnProperty(prefix = "aegis.interpretation", name = "enabled", havingValue = "true")
class FindingInterpretationJob {

    private static final Logger log = LoggerFactory.getLogger(FindingInterpretationJob.class);

    private final FindingInterpretations interpretations;
    private final IdentityInternalApi identity;
    private final CurrentOrg currentOrg;
    private final SchedulerLease lease;
    private final int maxPerRun;
    private final int maxPerDay;
    private final Clock clock;
    private LocalDate day;
    private int readToday;

    @Autowired
    FindingInterpretationJob(FindingInterpretations interpretations, IdentityInternalApi identity, CurrentOrg currentOrg,
            SchedulerLease lease, @Value("${aegis.interpretation.max-per-run:3}") int maxPerRun,
            @Value("${aegis.interpretation.max-per-day:60}") int maxPerDay) {
        this(interpretations, identity, currentOrg, lease, maxPerRun, maxPerDay, Clock.systemUTC());
    }

    FindingInterpretationJob(FindingInterpretations interpretations, IdentityInternalApi identity, CurrentOrg currentOrg,
            SchedulerLease lease, int maxPerRun, int maxPerDay, Clock clock) {
        this.interpretations = interpretations;
        this.identity = identity;
        this.currentOrg = currentOrg;
        this.lease = lease;
        this.maxPerRun = maxPerRun;
        this.maxPerDay = maxPerDay;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${aegis.interpretation.interval:PT2M}",
            initialDelayString = "${aegis.interpretation.initial-delay:PT1M}")
    void run() {
        if (!lease.held()) {
            return;
        }
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(day)) {
            day = today;
            readToday = 0;
        }
        if (readToday >= maxPerDay) {
            return;
        }
        List<UUID> organizations;
        try {
            organizations = new ArrayList<>(identity.organizationIds());
        } catch (RuntimeException unavailable) {
            log.warn("finding interpretation: could not list the organizations ({}); trying again next run",
                    unavailable.getMessage());
            return;
        }
        Collections.shuffle(organizations);
        AtomicInteger remaining = new AtomicInteger(Math.min(maxPerRun, maxPerDay - readToday));
        AtomicBoolean stopped = new AtomicBoolean();
        for (UUID org : organizations) {
            if (remaining.get() <= 0 || stopped.get()) {
                break;
            }
            try {
                currentOrg.runAs(org, () -> {
                    FindingInterpretations.Run run = interpretations.interpretNext(remaining.get());
                    remaining.addAndGet(-run.interpreted());
                    readToday += run.interpreted();
                    stopped.set(run.stopped());
                });
            } catch (RuntimeException failed) {
                // One organization's data must never stop the others being read.
                log.warn("finding interpretation: org {} skipped this run ({})", org, failed.toString());
            }
        }
    }
}
