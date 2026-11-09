package com.aegis.core.actions.triage;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.aegis.core.platform.identity.IdentityInternalApi;
import com.aegis.core.platform.scheduling.SchedulerLease;
import com.aegis.core.platform.tenant.CurrentOrg;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A background job shares a small AI budget with people asking for drafts, so it must never take much of it. */
class FindingInterpretationJobTest {
    private final FindingInterpretations interpretations = mock(FindingInterpretations.class);
    private final IdentityInternalApi identity = mock(IdentityInternalApi.class);
    private final SchedulerLease lease = mock(SchedulerLease.class);
    private final CurrentOrg currentOrg = new CurrentOrg();
    private final List<Integer> asked = new ArrayList<>();
    private final List<UUID> seen = new ArrayList<>();

    private FindingInterpretationJob job(int perRun, int perDay, Clock clock) {
        return new FindingInterpretationJob(interpretations, identity, currentOrg, lease, perRun, perDay, clock);
    }

    private void readsAsManyAsAsked() {
        when(lease.held()).thenReturn(true);
        when(interpretations.interpretNext(anyInt())).thenAnswer(call -> {
            int limit = call.getArgument(0);
            asked.add(limit);
            seen.add(currentOrg.require());
            return new FindingInterpretations.Run(limit, false);
        });
    }

    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    @Test
    void anInstanceWithoutTheLeaseDoesNoWork() {
        job(3, 60, Clock.systemUTC()).run();

        verifyNoInteractions(interpretations, identity);
    }

    @Test
    void theRunsBudgetIsSharedAcrossOrganizations() {
        readsAsManyAsAsked();
        when(identity.organizationIds()).thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));

        job(3, 60, Clock.systemUTC()).run();

        assertThat(asked).containsExactly(3);
        assertThat(seen).hasSize(1);
    }

    @Test
    void whatOneOrganizationLeavesGoesToTheNext() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(lease.held()).thenReturn(true);
        when(identity.organizationIds()).thenReturn(List.of(first, second));
        when(interpretations.interpretNext(anyInt())).thenAnswer(call -> {
            seen.add(currentOrg.require());
            return new FindingInterpretations.Run(1, false);
        });

        job(3, 60, Clock.systemUTC()).run();

        assertThat(seen).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void aFailureEndsTheRunForEveryOrganization() {
        when(lease.held()).thenReturn(true);
        when(identity.organizationIds()).thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));
        when(interpretations.interpretNext(anyInt())).thenAnswer(call -> {
            seen.add(currentOrg.require());
            return new FindingInterpretations.Run(0, true);
        });

        job(3, 60, Clock.systemUTC()).run();

        assertThat(seen).hasSize(1);
    }

    @Test
    void oneOrganizationsBadDataDoesNotStopTheNext() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(lease.held()).thenReturn(true);
        when(identity.organizationIds()).thenReturn(List.of(first, second));
        when(interpretations.interpretNext(anyInt())).thenAnswer(call -> {
            seen.add(currentOrg.require());
            if (seen.size() == 1) {
                throw new IllegalStateException("bad data");
            }
            return new FindingInterpretations.Run(0, false);
        });

        job(3, 60, Clock.systemUTC()).run();

        assertThat(seen).containsExactlyInAnyOrder(first, second);
        assertThat(currentOrg.orgId()).isEmpty();
    }

    @Test
    void stopsForTheDayAtTheDailyCap() {
        readsAsManyAsAsked();
        when(identity.organizationIds()).thenReturn(List.of(UUID.randomUUID()));
        FindingInterpretationJob capped = job(3, 4, at("2026-11-09T10:00:00Z"));

        capped.run();
        capped.run();
        capped.run();

        assertThat(asked).containsExactly(3, 1);
    }

    @Test
    void aNewUtcDayStartsAFreshAllowance() {
        readsAsManyAsAsked();
        when(identity.organizationIds()).thenReturn(List.of(UUID.randomUUID()));
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-09T23:00:00Z"));
        FindingInterpretationJob capped = job(3, 3, new Clock() {
            @Override public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override public Instant instant() {
                return now.get();
            }
        });

        capped.run();
        capped.run();
        now.set(Instant.parse("2026-11-10T00:30:00Z"));
        capped.run();

        assertThat(asked).containsExactly(3, 3);
    }

    @Test
    void waitsForTheNextRunWhenTheOrganizationsCannotBeListed() {
        when(lease.held()).thenReturn(true);
        when(identity.organizationIds()).thenThrow(new IllegalStateException("identity-service down"));

        job(3, 60, Clock.systemUTC()).run();

        verifyNoInteractions(interpretations);
    }
}
