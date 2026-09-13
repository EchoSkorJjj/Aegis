package com.aegis.core.ingestion;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionSchedulerTest {

    private final InMemoryRawEventStore store = new InMemoryRawEventStore();
    private final IngestionRetry retry = new IngestionRetry(new IngestionProperties(
            new IngestionProperties.Retry(2, Duration.ZERO, 2.0, Duration.ZERO)));

    private IngestionScheduler scheduler(IngestionSource... sources) {
        return new IngestionScheduler(List.of(sources), store, retry);
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
    }
}
