package com.aegis.core.actions.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.aegis.core.actions.dashboard.PostureSnapshotStore.History;
import com.aegis.core.actions.dashboard.PostureSnapshotStore.RecordedSnapshot;
import com.aegis.core.actions.dashboard.RiskPosture.Breakdown;
import com.aegis.core.actions.dashboard.RiskPosture.Snapshot;
import com.aegis.core.actions.ticket.ResponseStage;
import com.aegis.core.actions.triage.TriageQueue;
import com.aegis.core.actions.triage.TriageQueue.Item;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.matching.MatchingDtos.OwnerRef;
import com.aegis.core.matching.MatchingDtos.ServiceRef;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RiskPostureTest {
    private static final ServiceRef PAYMENTS = service("Payments");
    private static final ServiceRef WORKER = service("Worker");
    private static final ServiceRef QUIET = service("Quiet");
    private static final OwnerRef MORGAN = new OwnerRef(UUID.randomUUID(), UUID.randomUUID(), "Morgan");
    private static final OwnerRef ALEX = new OwnerRef(UUID.randomUUID(), UUID.randomUUID(), "Alex");

    @Test
    void openStagesCountOnceOverallAndForEachOwnerIncludingUnassignedAndZeroServices() {
        List<Item> items = List.of(
                item(PAYMENTS, List.of(MORGAN, ALEX), ResponseStage.NEW, ImpactTier.CRITICAL, 100),
                item(PAYMENTS, List.of(MORGAN), ResponseStage.IN_REVIEW, ImpactTier.HIGH, 80),
                item(WORKER, List.of(), ResponseStage.APPROVED, ImpactTier.MEDIUM, 50),
                item(WORKER, List.of(), ResponseStage.OVERRIDDEN, ImpactTier.LOW, 10),
                item(QUIET, List.of(), ResponseStage.ACTIONED, ImpactTier.CRITICAL, 100),
                item(QUIET, List.of(ALEX), ResponseStage.CLOSED, ImpactTier.CRITICAL, 100));
        var result = RiskPosture.aggregate(estate(items), Instant.parse("2026-10-22T00:00:00Z"));
        assertThat(result.totals()).isEqualTo(new RiskPosture.Totals(4, 1, 1, 1, 1, 2, 2, 2, 3));
        assertThat(result.services()).extracting(Breakdown::name).containsExactly("Payments", "Worker", "Quiet");
        assertThat(result.services().getLast().topScore()).isNull();
        assertThat(result.owners()).filteredOn(row -> MORGAN.id().equals(row.id()))
                .extracting(Breakdown::openItems).containsExactly(2);
        assertThat(result.owners()).filteredOn(row -> ALEX.id().equals(row.id()))
                .extracting(Breakdown::openItems).containsExactly(1);
        assertThat(result.owners()).filteredOn(row -> row.id() == null)
                .extracting(Breakdown::openItems).containsExactly(2);
        assertThat(result.owners().stream().mapToInt(Breakdown::openItems).sum()).isEqualTo(5);
    }

    @Test
    void anEmptyEstateHasZeroCountsAndNoInventedHistory() {
        TriageQueue queue = mock(TriageQueue.class);
        PostureSnapshotStore store = mock(PostureSnapshotStore.class);
        when(queue.overview()).thenReturn(new TriageQueue.Queue(new PageImpl<>(List.of()), List.of(), List.of(), List.of()));
        when(store.historyBetween(any(), any())).thenReturn(new History(List.of(), List.of()));
        var result = new RiskPosture(queue, store).read(7);
        assertThat(result.totals().openItems()).isZero();
        assertThat(result.totals().totalServices()).isZero();
        assertThat(result.trend()).isEmpty();
        assertThat(result.from()).isEqualTo(result.to().minusDays(6));
        assertThat(result.to()).isEqualTo(result.asOf().atOffset(java.time.ZoneOffset.UTC).toLocalDate());
        verify(store).save(new RecordedSnapshot(new Snapshot(result.to(), result.asOf(), 0, 0, 0), List.of(),
                List.of(new Breakdown(null, "Unassigned", 0, 0, 0, null))));
    }

    @Test
    void theTrendUsesStoredObservationsWithoutFillingInMissingDays() {
        TriageQueue queue = mock(TriageQueue.class);
        PostureSnapshotStore store = mock(PostureSnapshotStore.class);
        when(queue.overview()).thenReturn(estate(List.of(item(PAYMENTS, List.of(MORGAN), ResponseStage.NEW,
                ImpactTier.CRITICAL, 100))));
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        Snapshot previous = new Snapshot(today.minusDays(2), Instant.now().minusSeconds(172800), 4, 2, 1);
        RecordedSnapshot recorded = new RecordedSnapshot(previous,
                List.of(new Breakdown(PAYMENTS.id(), PAYMENTS.name(), 4, 2, 1, 100.0)),
                List.of(new Breakdown(MORGAN.id(), MORGAN.name(), 4, 2, 1, 100.0)));
        when(store.historyBetween(any(), any())).thenReturn(new History(List.of(previous), List.of(recorded)));
        var result = new RiskPosture(queue, store).read(30);
        assertThat(result.totals().openItems()).isEqualTo(1);
        assertThat(result.trend()).containsExactly(previous);
        assertThat(result.history()).containsExactly(recorded);
        assertThat(result.from()).isEqualTo(today.minusDays(29));
    }

    @Test
    void invalidPeriodsAreRejectedBeforeWorkingOutTheQueue() {
        TriageQueue queue = mock(TriageQueue.class);
        PostureSnapshotStore store = mock(PostureSnapshotStore.class);
        RiskPosture posture = new RiskPosture(queue, store);
        for (int days : List.of(-1, 0, 1, 100000)) {
            assertThatThrownBy(() -> posture.read(days)).isInstanceOf(ApiExceptions.BadRequest.class);
        }
        verifyNoInteractions(queue, store);
    }

    private static TriageQueue.Queue estate(List<Item> items) {
        return new TriageQueue.Queue(new PageImpl<>(items), List.of(PAYMENTS, QUIET, WORKER), List.of(MORGAN, ALEX), List.of());
    }

    private static ServiceRef service(String name) {
        return new ServiceRef(UUID.randomUUID(), name, BusinessCriticality.CRITICAL, ServiceExposure.INTERNET_FACING);
    }

    private static Item item(ServiceRef service, List<OwnerRef> owners, ResponseStage stage, ImpactTier tier, double score) {
        return new Item(TriageQueue.Kind.VULNERABILITY, UUID.randomUUID(), service, owners, score, tier,
                MatchConfidence.CONFIRMED, null, null, null, stage, false);
    }
}
