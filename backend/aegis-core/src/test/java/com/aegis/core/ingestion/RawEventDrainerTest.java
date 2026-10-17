package com.aegis.core.ingestion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link RawEventDrainer}'s outcome accounting and, for CS203-47, that each drain
 * pass reports its per-feed normalize counts to {@link IngestionFeedMetrics}.
 */
class RawEventDrainerTest {

    private static final String SOURCE_ID = "nvd";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private RawEventDrainer drainer(int maxAttempts, StoredRawEvent... pending) {
        IngestionFeedMetrics metrics = new IngestionFeedMetrics(
                registry, List.of(new FakeSource(SOURCE_ID)), new IngestionProperties(null, 5, null));
        return new RawEventDrainer(
                new InMemoryRawEventStore(List.of(pending)),
                new IngestionProperties(null, maxAttempts, null),
                metrics);
    }

    private record FakeSource(String sourceId) implements IngestionSource {
        @Override
        public List<RawEvent> poll() {
            return List.of();
        }
    }

    private static StoredRawEvent pending(String externalId, int attempts) {
        return new StoredRawEvent(
                UUID.randomUUID(), SOURCE_ID, externalId, "application/json", "{}", Instant.now(),
                Instant.now(), attempts);
    }

    @Test
    void everyEventHandledSuccessfullyIsNormalisedAndRecordedAsSuchInMetrics() {
        RawEventDrainer drainer = drainer(5, pending("CVE-1", 0), pending("CVE-2", 0));

        int handled = drainer.drain(SOURCE_ID, 10, event -> { });

        assertThat(handled).isEqualTo(2);
        assertThat(normalizeRecords("normalized")).isEqualTo(2);
        assertThat(normalizeRecords("dropped")).isZero();
        assertThat(normalizeRecords("deferred")).isZero();
    }

    @Test
    void anUnparseableEventIsDroppedImmediatelyAndRecordedAsDropped() {
        RawEventDrainer drainer = drainer(5, pending("CVE-1", 0));

        int handled = drainer.drain(SOURCE_ID, 10, event -> {
            throw new UnparseableEventException(SOURCE_ID, event.externalId(), new RuntimeException("bad json"));
        });

        assertThat(handled).isZero();
        assertThat(normalizeRecords("dropped")).isEqualTo(1);
        assertThat(normalizeRecords("normalized")).isZero();
    }

    @Test
    void aTransientFailureUnderTheAttemptCapIsDeferredNotDropped() {
        RawEventDrainer drainer = drainer(5, pending("CVE-1", 0));

        int handled = drainer.drain(SOURCE_ID, 10, event -> {
            throw new IllegalStateException("db blip");
        });

        assertThat(handled).isZero();
        assertThat(normalizeRecords("deferred")).isEqualTo(1);
        assertThat(normalizeRecords("dropped")).isZero();
    }

    @Test
    void aTransientFailureAtTheAttemptCapIsDroppedNotDeferred() {
        // attempts already at 0, maxAttempts=1: this failure's computed attempt count (1) meets the cap.
        RawEventDrainer drainer = drainer(1, pending("CVE-1", 0));

        int handled = drainer.drain(SOURCE_ID, 10, event -> {
            throw new IllegalStateException("still failing");
        });

        assertThat(handled).isZero();
        assertThat(normalizeRecords("dropped")).isEqualTo(1);
        assertThat(normalizeRecords("deferred")).isZero();
    }

    @Test
    void normalizeOutcomesAreReportedEvenWhenNothingWasPending() {
        RawEventDrainer drainer = drainer(5);

        int handled = drainer.drain(SOURCE_ID, 10, event -> { });

        assertThat(handled).isZero();
        assertThat(normalizeRecords("normalized")).isZero();
    }

    private double normalizeRecords(String result) {
        return registry.get("aegis.ingestion.normalize.records")
                .tag("source_id", SOURCE_ID).tag("result", result).counter().count();
    }

    /** A queue of not-yet-processed events; {@code markProcessed} removes an id from the queue. */
    private static final class InMemoryRawEventStore implements RawEventStore {
        private final List<StoredRawEvent> pending;
        private final Set<UUID> processed = new java.util.HashSet<>();
        private final java.util.Map<UUID, Integer> extraAttempts = new java.util.HashMap<>();

        InMemoryRawEventStore(List<StoredRawEvent> pending) {
            this.pending = new ArrayList<>(pending);
        }

        @Override
        public boolean saveIfNew(String sourceId, RawEvent event) {
            throw new UnsupportedOperationException("not exercised by the drainer");
        }

        @Override
        public List<StoredRawEvent> findUnprocessed(String sourceId, int limit) {
            return pending.stream()
                    .filter(event -> !processed.contains(event.id()))
                    .map(this::withRecordedAttempts)
                    .limit(limit)
                    .toList();
        }

        private StoredRawEvent withRecordedAttempts(StoredRawEvent event) {
            Integer extra = extraAttempts.get(event.id());
            if (extra == null) {
                return event;
            }
            return new StoredRawEvent(event.id(), event.sourceId(), event.externalId(), event.contentType(),
                    event.payload(), event.occurredAt(), event.ingestedAt(), event.attempts() + extra);
        }

        @Override
        public void markProcessed(UUID id) {
            processed.add(id);
        }

        @Override
        public void recordFailure(UUID id) {
            extraAttempts.merge(id, 1, Integer::sum);
        }

        @Override
        public Optional<Instant> latestOccurredAt(String sourceId) {
            return Optional.empty();
        }
    }
}
