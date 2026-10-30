package com.aegis.core.ingestion.connectors.osv;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.aegis.core.ingestion.IngestionFeedMetrics;
import com.aegis.core.ingestion.IngestionProperties;
import com.aegis.core.ingestion.RawEvent;
import com.aegis.core.ingestion.RawEventStore;
import com.aegis.core.platform.scheduling.SchedulerLease;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The OSV source streams a bulk export straight into the raw-event store; it is not a windowed feed.
 * These unit tests pin that contract: {@code sync()} stores as it streams, and each record's raw-event id
 * is keyed on the payload so a revision re-normalises while an unchanged record de-duplicates on re-download.
 */
class OsvSourceTest {

    private final SchedulerLease lease = mock(SchedulerLease.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final IngestionFeedMetrics metrics = new IngestionFeedMetrics(
            registry, List.of(), new IngestionProperties(null, 0, null));
    private final Clock clock = mock(Clock.class);
    private final Instant now = Instant.now();

    private OsvSource source(OsvClient client, RawEventStore store, OsvProperties properties) {
        when(clock.instant()).thenReturn(now);
        return new OsvSource(client, store, properties, lease, metrics, Duration.ofDays(1), Duration.ofMinutes(15),
                clock);
    }

    @Test
    void anInstanceWithoutTheLeaseDoesNotSync() {
        RawEventStore store = mock(RawEventStore.class);
        OsvClient client = (ecosystem, maxRecords, maxRecordBytes, consumer) ->
                consumer.accept("GHSA-aaa", "{\"id\":\"GHSA-aaa\"}");
        when(lease.held()).thenReturn(false);

        source(client, store, properties("npm")).scheduledSync();

        verify(store, never()).saveIfNew(any(), any());
        assertThat(pollCount("success")).isZero();
        assertThat(pollCount("failure")).isZero();
    }

    private static OsvProperties properties(String... ecosystems) {
        return new OsvProperties(true, null, List.of(ecosystems), null, null, 0, 0, 0, false, false);
    }

    private static OsvProperties propertiesWithMalicious(String... ecosystems) {
        return new OsvProperties(true, null, List.of(ecosystems), null, null, 0, 0, 0, false, true);
    }

    @Test
    void streamsEachRecordIntoTheStoreAndCountsNewOnes() throws Exception {
        RawEventStore store = mock(RawEventStore.class);
        when(store.saveIfNew(eq(OsvSource.SOURCE_ID), any())).thenReturn(true, false);
        OsvClient client = (ecosystem, maxRecords, maxRecordBytes, consumer) -> {
            consumer.accept("GHSA-aaa", "{\"id\":\"GHSA-aaa\"}");
            consumer.accept("GHSA-bbb", "{\"id\":\"GHSA-bbb\"}");
        };

        int stored = source(client, store, properties("npm")).sync();

        // Records go straight to the store as they stream; the count reflects only the new (not duplicate) ones.
        assertThat(stored).isEqualTo(1); // first saveIfNew -> true (new), second -> false (duplicate)
        assertThat(pollCount("success")).isEqualTo(1);
        assertThat(registry.get("aegis.ingestion.health").tag("source_id", "osv").gauge().value()).isEqualTo(1);
        assertThat(registry.get("aegis.ingestion.last.success.timestamp.seconds")
                .tag("source_id", "osv").gauge().value()).isFinite();
        assertThat(registry.get("aegis.ingestion.poll.records")
                .tag("source_id", "osv").tag("result", "stored").counter().count()).isEqualTo(1);
        assertThat(registry.get("aegis.ingestion.poll.records")
                .tag("source_id", "osv").tag("result", "duplicate").counter().count()).isEqualTo(1);

        ArgumentCaptor<RawEvent> captor = ArgumentCaptor.forClass(RawEvent.class);
        verify(store, times(2)).saveIfNew(eq(OsvSource.SOURCE_ID), captor.capture());
        List<RawEvent> saved = captor.getAllValues();
        assertThat(saved).extracting(RawEvent::externalId)
                .allSatisfy(id -> assertThat(id).contains("@")); // {osvId}@{fingerprint}
        assertThat(saved.get(0).externalId()).startsWith("GHSA-aaa@");
        assertThat(saved.get(1).externalId()).startsWith("GHSA-bbb@");
        assertThat(saved).extracting(RawEvent::contentType).containsOnly("application/json");
    }

    @Test
    void keysTheRawEventOnContentSoRevisionsReNormaliseButUnchangedRecordsRepeatTheirId() throws Exception {
        RawEventStore store = mock(RawEventStore.class);
        when(store.saveIfNew(eq(OsvSource.SOURCE_ID), any())).thenReturn(true);
        // The same OSV id seen twice unchanged, then revised — the store keys de-duplication on externalId.
        OsvClient client = (ecosystem, maxRecords, maxRecordBytes, consumer) -> {
            consumer.accept("GHSA-aaa", "{\"modified\":\"2099-01-01\"}");
            consumer.accept("GHSA-aaa", "{\"modified\":\"2099-01-01\"}");
            consumer.accept("GHSA-aaa", "{\"modified\":\"2099-02-02\"}");
        };

        source(client, store, properties("npm")).sync();

        ArgumentCaptor<RawEvent> captor = ArgumentCaptor.forClass(RawEvent.class);
        verify(store, times(3)).saveIfNew(eq(OsvSource.SOURCE_ID), captor.capture());
        List<String> ids = captor.getAllValues().stream().map(RawEvent::externalId).toList();
        assertThat(ids).allSatisfy(id -> assertThat(id).startsWith("GHSA-aaa@"));
        assertThat(ids.get(0)).isEqualTo(ids.get(1));      // unchanged content → identical id → dedup
        assertThat(ids.get(2)).isNotEqualTo(ids.get(0));   // revised content → new id → re-normalise
    }

    @Test
    void skipsMaliciousPackageReportsByDefaultButKeepsRealAdvisories() throws Exception {
        RawEventStore store = mock(RawEventStore.class);
        when(store.saveIfNew(eq(OsvSource.SOURCE_ID), any())).thenReturn(true);
        OsvClient client = (ecosystem, maxRecords, maxRecordBytes, consumer) -> {
            consumer.accept("MAL-2025-1", "{\"id\":\"MAL-2025-1\"}"); // OSV malicious-package report
            consumer.accept("GHSA-aaa", "{\"id\":\"GHSA-aaa\"}");      // real advisory
            consumer.accept("CVE-2025-1", "{\"id\":\"CVE-2025-1\"}");  // real advisory
        };

        int stored = source(client, store, properties("npm")).sync();

        // Only the two real advisories are stored; the MAL-* report never reaches the store.
        assertThat(stored).isEqualTo(2);
        ArgumentCaptor<RawEvent> captor = ArgumentCaptor.forClass(RawEvent.class);
        verify(store, times(2)).saveIfNew(eq(OsvSource.SOURCE_ID), captor.capture());
        assertThat(captor.getAllValues()).extracting(RawEvent::externalId)
                .noneMatch(id -> id.startsWith("MAL-"));
    }

    @Test
    void includesMaliciousPackageReportsWhenOptedIn() throws Exception {
        RawEventStore store = mock(RawEventStore.class);
        when(store.saveIfNew(eq(OsvSource.SOURCE_ID), any())).thenReturn(true);
        OsvClient client = (ecosystem, maxRecords, maxRecordBytes, consumer) ->
                consumer.accept("MAL-2025-1", "{\"id\":\"MAL-2025-1\"}");

        int stored = source(client, store, propertiesWithMalicious("npm")).sync();

        assertThat(stored).isEqualTo(1); // opted in — the malicious-package report is stored
        verify(store, times(1)).saveIfNew(eq(OsvSource.SOURCE_ID), any());
    }

    @Test
    void iteratesEveryConfiguredEcosystem() throws Exception {
        RawEventStore store = mock(RawEventStore.class);
        when(store.saveIfNew(eq(OsvSource.SOURCE_ID), any())).thenReturn(true);
        OsvClient client = (ecosystem, maxRecords, maxRecordBytes, consumer) ->
                consumer.accept("REC-" + ecosystem, "{\"ecosystem\":\"" + ecosystem + "\"}");

        source(client, store, properties("npm", "PyPI", "crates.io")).sync();

        ArgumentCaptor<RawEvent> captor = ArgumentCaptor.forClass(RawEvent.class);
        verify(store, times(3)).saveIfNew(eq(OsvSource.SOURCE_ID), captor.capture());
        assertThat(captor.getAllValues()).extracting(RawEvent::externalId)
                .anySatisfy(id -> assertThat(id).startsWith("REC-npm@"))
                .anySatisfy(id -> assertThat(id).startsWith("REC-PyPI@"))
                .anySatisfy(id -> assertThat(id).startsWith("REC-crates.io@"));
    }

    @Test
    void partialSyncFailureIsUnhealthyAndTheNextSuccessfulSyncRecovers() throws Exception {
        RawEventStore store = mock(RawEventStore.class);
        when(lease.held()).thenReturn(true);
        OsvClient client = mock(OsvClient.class);
        OsvSource source = source(client, store, properties("npm", "PyPI"));
        metrics.recordPollSuccess("osv", 0, 0);
        doThrow(new IllegalStateException("unavailable"))
                .when(client).forEachRecord(eq("PyPI"), anyInt(), anyInt(), any());

        source.scheduledSync();

        assertThat(pollCount("failure")).isEqualTo(1);
        assertThat(pollCount("success")).isEqualTo(1);
        assertThat(registry.get("aegis.ingestion.health").tag("source_id", "osv").gauge().value()).isZero();
        reset(client);
        when(clock.instant()).thenReturn(now.plus(Duration.ofDays(1)));
        source.scheduledSync();
        assertThat(pollCount("success")).isEqualTo(2);
        assertThat(registry.get("aegis.ingestion.health").tag("source_id", "osv").gauge().value()).isEqualTo(1);
    }

    @Test
    void missingTheLeaseAtStartupDoesNotPostponeTheFirstSyncForADay() {
        RawEventStore store = mock(RawEventStore.class);
        OsvClient client = (ecosystem, maxRecords, maxRecordBytes, consumer) -> {};
        when(lease.held()).thenReturn(false, true);
        OsvSource source = source(client, store, properties("npm"));

        source.scheduledSync();
        assertThat(pollCount("success")).isZero();
        source.scheduledSync();
        assertThat(pollCount("success")).isEqualTo(1);
    }

    @Test
    void frequentLeaseChecksDoNotDownloadAgainUntilTheDailySyncIsDue() {
        RawEventStore store = mock(RawEventStore.class);
        OsvClient client = (ecosystem, maxRecords, maxRecordBytes, consumer) -> {};
        when(lease.held()).thenReturn(true);
        OsvSource source = source(client, store, properties("npm"));

        source.scheduledSync();
        when(clock.instant()).thenReturn(now.plus(Duration.ofHours(23)));
        source.scheduledSync();
        assertThat(pollCount("success")).isEqualTo(1);
        when(clock.instant()).thenReturn(now.plus(Duration.ofDays(1)));
        source.scheduledSync();
        assertThat(pollCount("success")).isEqualTo(2);
    }

    @Test
    void aFailedSyncIsRetriedAfterTheRetryDelayInsteadOfTheNextDay() throws Exception {
        RawEventStore store = mock(RawEventStore.class);
        when(lease.held()).thenReturn(true);
        OsvClient client = mock(OsvClient.class);
        doThrow(new IllegalStateException("unavailable")).doNothing()
                .when(client).forEachRecord(eq("npm"), anyInt(), anyInt(), any());
        OsvSource source = source(client, store, properties("npm"));

        source.scheduledSync();
        assertThat(pollCount("failure")).isEqualTo(1);
        when(clock.instant()).thenReturn(now.plus(Duration.ofMinutes(14)));
        source.scheduledSync();
        verify(client, times(1)).forEachRecord(eq("npm"), anyInt(), anyInt(), any());

        when(clock.instant()).thenReturn(now.plus(Duration.ofMinutes(15)));
        source.scheduledSync();
        assertThat(pollCount("success")).isEqualTo(1);
        assertThat(registry.get("aegis.ingestion.health").tag("source_id", "osv").gauge().value()).isEqualTo(1);

        // Recovered, so back to the daily cadence.
        when(clock.instant()).thenReturn(now.plus(Duration.ofMinutes(15)).plus(Duration.ofHours(23)));
        source.scheduledSync();
        assertThat(pollCount("success")).isEqualTo(1);
    }

    @Test
    void repeatedFailuresBackOffButNeverWaitLongerThanThePollInterval() {
        AtomicReference<Instant> time = new AtomicReference<>(now);
        when(clock.instant()).thenAnswer(call -> time.get());
        when(lease.held()).thenReturn(true);
        List<Long> attemptedAtMinute = new ArrayList<>();
        OsvClient client = (ecosystem, maxRecords, maxRecordBytes, consumer) -> {
            attemptedAtMinute.add(Duration.between(now, time.get()).toMinutes());
            throw new IllegalStateException("unavailable");
        };
        OsvSource source = new OsvSource(client, mock(RawEventStore.class), properties("npm"), lease, metrics,
                Duration.ofHours(1), Duration.ofMinutes(15), clock);

        for (int minute = 0; minute <= 180; minute++) {
            time.set(now.plus(Duration.ofMinutes(minute)));
            source.scheduledSync();
        }

        // 15, then 30 minutes apart; then capped at the hourly poll interval.
        assertThat(attemptedAtMinute).containsExactly(0L, 15L, 45L, 105L, 165L);
        assertThat(pollCount("failure")).isEqualTo(5);
    }

    @Test
    void aRetryDelayMustBePositive() {
        assertThatThrownBy(() -> new OsvSource((ecosystem, maxRecords, maxRecordBytes, consumer) -> { },
                mock(RawEventStore.class), properties("npm"), lease, metrics, Duration.ofDays(1), Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private double pollCount(String outcome) {
        return registry.get("aegis.ingestion.poll").tag("source_id", "osv")
                .tag("outcome", outcome).counter().count();
    }
}
