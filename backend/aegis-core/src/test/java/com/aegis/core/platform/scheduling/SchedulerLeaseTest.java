package com.aegis.core.platform.scheduling;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchedulerLeaseTest {

    private final FakeStore store = new FakeStore();
    private final SchedulerLease lease = new SchedulerLease(store, "jobs", "instance-a", Duration.ofMinutes(2));

    @Test
    void runsTheJobsOnlyWhileTheStoreGrantsTheLease() {
        store.grants = true;
        assertThat(lease.held()).isTrue();

        store.grants = false;
        assertThat(lease.held()).isFalse();
    }

    @Test
    void doesNotRunTheJobsWhenTheLeaseCantBeChecked() {
        store.failure = new IllegalStateException("database unreachable");

        assertThat(lease.held()).isFalse();
    }

    @Test
    void givesTheLeaseUpOnShutdownOnlyIfItHadIt() {
        lease.release();
        assertThat(store.released).isEmpty();

        store.grants = true;
        lease.held();
        lease.release();
        assertThat(store.released).containsExactly("jobs by instance-a");
    }

    @Test
    void renewingIsAskingAgain() {
        store.grants = true;

        lease.renew();

        assertThat(store.asked).containsExactly("jobs by instance-a for PT2M");
    }

    @Test
    void refusesALeaseThatNeverLasts() {
        assertThatThrownBy(() -> new SchedulerLease(store, "jobs", "instance-a", Duration.ZERO))
                .isInstanceOf(IllegalStateException.class);
    }

    private static final class FakeStore implements SchedulerLeaseStore {
        boolean grants;
        RuntimeException failure;
        final List<String> asked = new ArrayList<>();
        final List<String> released = new ArrayList<>();

        @Override
        public boolean acquireOrRenew(String name, String holder, Duration duration) {
            if (failure != null) {
                throw failure;
            }
            asked.add(name + " by " + holder + " for " + duration);
            return grants;
        }

        @Override
        public void release(String name, String holder) {
            released.add(name + " by " + holder);
        }
    }
}
