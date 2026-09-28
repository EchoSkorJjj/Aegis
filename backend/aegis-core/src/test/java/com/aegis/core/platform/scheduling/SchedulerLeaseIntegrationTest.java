package com.aegis.core.platform.scheduling;

import java.time.Duration;
import java.util.UUID;

import com.aegis.core.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two aegis-core instances sharing one database, as during a rolling deploy: the scheduled jobs run on one of
 * them at a time. Each test uses its own lease name, since the suite shares the database.
 */
class SchedulerLeaseIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SchedulerLeaseStore store;

    @Test
    void oneInstanceHoldsTheLeaseAndKeepsItWhileItRenews() {
        String name = uniqueName();
        SchedulerLease first = instance(name, "old-task", Duration.ofMinutes(2));
        SchedulerLease second = instance(name, "new-task", Duration.ofMinutes(2));

        assertThat(first.held()).isTrue();
        assertThat(second.held()).isFalse();
        assertThat(first.held()).isTrue();
        assertThat(second.held()).isFalse();
    }

    @Test
    void aStoppingInstanceHandsTheLeaseOver() {
        String name = uniqueName();
        SchedulerLease first = instance(name, "old-task", Duration.ofMinutes(2));
        SchedulerLease second = instance(name, "new-task", Duration.ofMinutes(2));
        assertThat(first.held()).isTrue();

        first.release();

        assertThat(second.held()).isTrue();
        assertThat(first.held()).isFalse();
    }

    @Test
    void aDeadInstancesLeaseExpires() throws InterruptedException {
        String name = uniqueName();
        SchedulerLease crashed = instance(name, "crashed-task", Duration.ofMillis(500));
        SchedulerLease standby = instance(name, "standby-task", Duration.ofMinutes(2));
        assertThat(crashed.held()).isTrue();
        assertThat(standby.held()).isFalse();

        Thread.sleep(800);

        assertThat(standby.held()).isTrue();
    }

    private SchedulerLease instance(String name, String holder, Duration duration) {
        return new SchedulerLease(store, name, holder, duration);
    }

    private static String uniqueName() {
        return "test-" + UUID.randomUUID();
    }
}
