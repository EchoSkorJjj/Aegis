package com.aegis.core.ingestion;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Per-feed ingestion observability (CS203-47): health, record counts, and last-success time, each
 * tagged by {@code source_id} (an {@link IngestionSource#sourceId()}) and exposed as Micrometer
 * meters — scraped today at {@code /actuator/prometheus} (already wired; see {@code ops/README.md}).
 *
 * <p>Every configured {@link IngestionSource} is registered eagerly at startup, so a feed's gauges
 * exist (reading unhealthy/no-data) from boot, not only after its first poll.
 */
@Component
class IngestionFeedMetrics {

    private static final String POLL = "aegis.ingestion.poll";
    private static final String POLL_RECORDS = "aegis.ingestion.poll.records";
    private static final String NORMALIZE_RECORDS = "aegis.ingestion.normalize.records";
    private static final String LAST_SUCCESS = "aegis.ingestion.last.success.timestamp.seconds";
    private static final String HEALTH = "aegis.ingestion.health";

    private final MeterRegistry registry;
    private final Duration staleAfter;
    private final Map<String, FeedState> feeds = new ConcurrentHashMap<>();

    IngestionFeedMetrics(MeterRegistry registry, List<IngestionSource> sources, IngestionProperties properties) {
        this.registry = registry;
        this.staleAfter = properties.health().staleAfter();
        sources.forEach(source -> stateFor(source.sourceId()));
    }

    /** A poll of {@code sourceId} completed without throwing; {@code stored}/{@code duplicate} raw events. */
    void recordPollSuccess(String sourceId, int stored, int duplicates) {
        FeedState state = stateFor(sourceId);
        state.lastSuccessAt.set(Instant.now());
        state.lastAttemptFailed.set(false);
        pollOutcomeCounter(sourceId, "success").increment();
        pollRecordsCounter(sourceId, "stored").increment(stored);
        pollRecordsCounter(sourceId, "duplicate").increment(duplicates);
    }

    /** A poll of {@code sourceId} failed (exhausted retries); see {@link IngestionRetry}. */
    void recordPollFailure(String sourceId) {
        stateFor(sourceId).lastAttemptFailed.set(true);
        pollOutcomeCounter(sourceId, "failure").increment();
    }

    /** A {@link RawEventDrainer#drain} pass for {@code sourceId} normalised/dropped/deferred this many events. */
    void recordNormalizeOutcome(String sourceId, int normalized, int dropped, int deferred) {
        stateFor(sourceId); // a normalizer can drain before its source ever successfully polls
        normalizeRecordsCounter(sourceId, "normalized").increment(normalized);
        normalizeRecordsCounter(sourceId, "dropped").increment(dropped);
        normalizeRecordsCounter(sourceId, "deferred").increment(deferred);
    }

    private FeedState stateFor(String sourceId) {
        return feeds.computeIfAbsent(sourceId, this::registerFeed);
    }

    private FeedState registerFeed(String sourceId) {
        FeedState state = new FeedState();
        Gauge.builder(LAST_SUCCESS, state.lastSuccessAt, ref -> {
                    Instant lastSuccess = ref.get();
                    return lastSuccess == null ? Double.NaN : lastSuccess.toEpochMilli() / 1000.0;
                })
                .tag("source_id", sourceId)
                .description("Unix timestamp (seconds) this feed last completed a poll successfully")
                .register(registry);
        Gauge.builder(HEALTH, state, s -> s.isHealthy(staleAfter) ? 1 : 0)
                .tag("source_id", sourceId)
                .description("1 if the feed is healthy (has succeeded recently), 0 otherwise")
                .register(registry);
        return state;
    }

    private Counter pollOutcomeCounter(String sourceId, String outcome) {
        return Counter.builder(POLL)
                .tag("source_id", sourceId)
                .tag("outcome", outcome)
                .description("Ingestion poll attempts per feed")
                .register(registry);
    }

    private Counter pollRecordsCounter(String sourceId, String result) {
        return Counter.builder(POLL_RECORDS)
                .tag("source_id", sourceId)
                .tag("result", result)
                .description("Raw events observed per feed poll, by result")
                .register(registry);
    }

    private Counter normalizeRecordsCounter(String sourceId, String result) {
        return Counter.builder(NORMALIZE_RECORDS)
                .tag("source_id", sourceId)
                .tag("result", result)
                .description("Raw events normalised per feed, by result")
                .register(registry);
    }

    /** Mutable per-feed state the health/last-success gauges sample live on every scrape. */
    private static final class FeedState {
        private final AtomicReference<Instant> lastSuccessAt = new AtomicReference<>();
        private final AtomicBoolean lastAttemptFailed = new AtomicBoolean(false);

        boolean isHealthy(Duration staleAfter) {
            Instant lastSuccess = lastSuccessAt.get();
            if (lastSuccess == null || lastAttemptFailed.get()) {
                return false;
            }
            return !Duration.between(lastSuccess, Instant.now()).minus(staleAfter).isPositive();
        }
    }
}
