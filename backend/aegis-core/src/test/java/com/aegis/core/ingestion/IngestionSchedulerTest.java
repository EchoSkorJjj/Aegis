package com.aegis.core.ingestion;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionSchedulerTest {

    private final InMemoryRawEventStore store = new InMemoryRawEventStore();
    private final IngestionRetry retry = new IngestionRetry(new IngestionProperties(
            new IngestionProperties.Retry(2, Duration.ZERO, 2.0, Duration.ZERO), 5, null));
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private IngestionScheduler scheduler(IngestionSource... sources) {
        List<IngestionSource> sourceList = List.of(sources);
        IngestionFeedMetrics metrics = new IngestionFeedMetrics(
                registry, sourceList, new IngestionProperties(null, 5, null));
        return new IngestionScheduler(sourceList, store, retry, metrics);
    }

    private static RawEvent event(String externalId) {
        return new RawEvent(externalId, "application/json", "{\"id\":\"" + externalId + "\"}", Instant.now());
    }

    @Test
    void noSourcesIsANoOp() {
        assertThat(scheduler().runOnce()).isZero();
        assertThat(store.stored).isEmpty();
    }

    @Test
    void pollsEverySourceAndStoresNewEvents() {
        IngestionScheduler scheduler = scheduler(
                new FakeSource("nvd", List.of(event("CVE-1"), event("CVE-2"))),
                new FakeSource("osv", List.of(event("GHSA-1"))));

        assertThat(scheduler.runOnce()).isEqualTo(3);
        assertThat(store.stored).hasSize(3);
    }

    @Test
    void deduplicatesWithinAndAcrossCycles() {
        IngestionScheduler scheduler = scheduler(
                new FakeSource("nvd", List.of(event("CVE-1"), event("CVE-1"), event("CVE-2"))));

        assertThat(scheduler.runOnce()).isEqualTo(2); // CVE-1 stored once, its repeat is a duplicate
        assertThat(scheduler.runOnce()).isZero(); // a re-poll of the same window stores nothing
        assertThat(store.stored).hasSize(2);
    }

    @Test
    void oneFailingSourceDoesNotStopTheOthers() {
        IngestionScheduler scheduler = scheduler(
                new FailingSource("broken"), new FakeSource("nvd", List.of(event("CVE-9"))));

        assertThat(scheduler.runOnce()).isEqualTo(1);
        assertThat(store.stored).containsExactly("nvd|CVE-9");
    }

    @Test
    void aSuccessfulPollRecordsHealthAndLastSuccessMetrics() {
        IngestionScheduler scheduler = scheduler(
                new FakeSource("nvd", List.of(event("CVE-1"), event("CVE-2"))));

        scheduler.runOnce();

        assertThat(registry.get("aegis.ingestion.health").tag("source_id", "nvd").gauge().value())
                .isEqualTo(1);
        assertThat(registry.get("aegis.ingestion.last.success.timestamp.seconds")
                        .tag("source_id", "nvd").gauge().value())
                .isNotNaN();
        assertThat(registry.get("aegis.ingestion.poll")
                .tag("source_id", "nvd").tag("outcome", "success").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("aegis.ingestion.poll.records")
                .tag("source_id", "nvd").tag("result", "stored").counter().count())
                .isEqualTo(2);
    }

    @Test
    void aFailingPollRecordsAnUnhealthyFeedAndAFailureCount() {
        IngestionScheduler scheduler = scheduler(new FailingSource("broken"));

        scheduler.runOnce();

        assertThat(registry.get("aegis.ingestion.health").tag("source_id", "broken").gauge().value())
                .isZero();
        assertThat(registry.get("aegis.ingestion.poll")
                .tag("source_id", "broken").tag("outcome", "failure").counter().count())
                .isEqualTo(1);
    }

    private record FakeSource(String id, List<RawEvent> events) implements IngestionSource {
        @Override
        public String sourceId() {
            return id;
        }

        @Override
        public List<RawEvent> poll() {
            return events;
        }
    }

    private record FailingSource(String id) implements IngestionSource {
        @Override
        public String sourceId() {
            return id;
        }

        @Override
        public List<RawEvent> poll() {
            throw new IllegalStateException("feed down");
        }
    }

    /** Mirrors the real store's contract: de-duplicate by (sourceId, externalId-or-payload). */
    private static final class InMemoryRawEventStore implements RawEventStore {
        private final Set<String> keys = new HashSet<>();
        private final List<String> stored = new ArrayList<>();

        @Override
        public boolean saveIfNew(String sourceId, RawEvent event) {
            String discriminator = event.externalId() != null ? event.externalId() : event.payload();
            String key = sourceId + "|" + discriminator;
            if (!keys.add(key)) {
                return false;
            }
            stored.add(key);
            return true;
        }

        // The scheduler only ever stores; normalisation and watermarks are exercised elsewhere.

        @Override
        public List<StoredRawEvent> findUnprocessed(String sourceId, int limit) {
            return List.of();
        }

        @Override
        public void markProcessed(UUID id) {
        }

        @Override
        public void recordFailure(UUID id) {
        }

        @Override
        public Optional<Instant> latestOccurredAt(String sourceId) {
            return Optional.empty();
        }
    }
}
