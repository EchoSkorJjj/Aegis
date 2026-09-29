package com.aegis.core.platform.scheduling;

import java.time.Duration;

/**
 * Where the scheduler lease lives ({@code scheduler_leases}, V30). The database's clock decides when a lease
 * expires, so the instances' own clocks needn't agree.
 */
public interface SchedulerLeaseStore {

    /**
     * Gives {@code holder} the lease until {@code duration} from now, if it's free, expired or already theirs.
     *
     * @return whether {@code holder} has the lease now
     */
    boolean acquireOrRenew(String name, String holder, Duration duration);

    /** Gives the lease up, if {@code holder} still has it. */
    void release(String name, String holder);
}
