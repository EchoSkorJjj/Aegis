package com.aegis.core.platform.scheduling;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lets one aegis-core instance at a time run the scheduled jobs: the feed polls, the normalisers and the OSV
 * sync. Two instances run side by side while a rolling deploy replaces one with the other, or when the
 * service is scaled out; without this each would run every job, fetching every feed twice and normalising
 * the same raw events at the same time.
 *
 * <p>The instance holding the lease ({@code scheduler_leases}, V30) runs the jobs and the others skip them.
 * Each job asks {@link #held()} before it runs, which takes a free or expired lease and extends one this
 * instance holds; {@link #renew()} keeps it between jobs. An instance that shuts down gives the lease up, so
 * a standby takes over at its next renewal; one that dies loses it when it expires
 * ({@code aegis.scheduling.lease-duration}).
 */
@Component
public class SchedulerLease {

    static final String NAME = "scheduled-jobs";

    private static final Logger log = LoggerFactory.getLogger(SchedulerLease.class);

    private final SchedulerLeaseStore store;
    private final String name;
    private final String holder;
    private final Duration duration;
    private volatile boolean held;

    @Autowired
    public SchedulerLease(SchedulerLeaseStore store, @Value("${aegis.scheduling.lease-duration:PT2M}") Duration duration) {
        this(store, NAME, instanceId(), duration);
    }

    SchedulerLease(SchedulerLeaseStore store, String name, String holder, Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalStateException("aegis.scheduling.lease-duration must be positive");
        }
        this.store = store;
        this.name = name;
        this.holder = holder;
        this.duration = duration;
    }

    /** Whether this instance should run the scheduled jobs now; takes or extends the lease. */
    public boolean held() {
        boolean now;
        try {
            now = store.acquireOrRenew(name, holder, duration);
        } catch (RuntimeException unknown) {
            // Can't tell (the database is unreachable, say): don't run; the job would fail anyway.
            log.warn("scheduler lease: could not check it: {}", unknown.toString());
            now = false;
        }
        if (now != held) {
            log.info(now ? "scheduler lease: {} runs the scheduled jobs" : "scheduler lease: {} stands by",
                    holder);
        }
        held = now;
        return now;
    }

    /** Keeps the lease between jobs (a job's interval can outlast it) and lets a standby take over. */
    @Scheduled(
            fixedDelayString = "${aegis.scheduling.lease-renewal:PT30S}",
            initialDelayString = "${aegis.scheduling.lease-renewal:PT30S}")
    void renew() {
        held();
    }

    /** Gives the lease up on shutdown, so the next instance needn't wait for it to expire. */
    @PreDestroy
    void release() {
        if (!held) {
            return;
        }
        try {
            store.release(name, holder);
            held = false;
        } catch (RuntimeException ex) {
            log.warn("scheduler lease: could not give it up; it expires in {}: {}", duration, ex.toString());
        }
    }

    /** The container's host name, where there is one, and a random suffix: unique per running process. */
    private static String instanceId() {
        String host = Optional.ofNullable(System.getenv("HOSTNAME")).filter(value -> !value.isBlank())
                .orElse("aegis-core");
        return host + "/" + UUID.randomUUID();
    }
}
