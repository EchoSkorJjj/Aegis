package com.aegis.core.ingestion;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Discovers every {@link IngestionSource} bean and polls it on a fixed schedule — applying
 * retry/backoff per source, de-duplicating, and persisting new events to the {@link RawEventStore}.
 * One source failing never stops the others. Until connectors are registered, a cycle is a no-op.
 *
 * <p>Disable entirely with {@code aegis.ingestion.enabled=false}; tune cadence with
 * {@code aegis.ingestion.poll-interval} (ISO-8601 duration, default {@code PT15M}). The first poll is
 * deferred by one interval so startup (and tests) are unaffected.
 */
@Component
@ConditionalOnProperty(name = "aegis.ingestion.enabled", havingValue = "true", matchIfMissing = true)
class IngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

    private final List<IngestionSource> sources;
    private final RawEventStore store;
    private final IngestionRetry retry;
    private final IngestionFeedMetrics metrics;

    IngestionScheduler(
            List<IngestionSource> sources, RawEventStore store, IngestionRetry retry, IngestionFeedMetrics metrics) {
        this.sources = sources;
        this.store = store;
        this.retry = retry;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${aegis.ingestion.poll-interval:PT15M}",
            initialDelayString = "${aegis.ingestion.poll-interval:PT15M}")
    void pollAll() {
        runOnce();
    }

    /**
     * Poll every registered source once. Returns the number of new events stored across all sources.
     * Package-visible so the orchestration can be unit-tested without the scheduler firing.
     */
    int runOnce() {
        if (sources.isEmpty()) {
            log.debug("ingestion: no sources registered");
            return 0;
        }
        int stored = 0;
        for (IngestionSource source : sources) {
            stored += pollSource(source);
        }
        return stored;
    }

    private int pollSource(IngestionSource source) {
        String sourceId = source.sourceId();
        try {
            List<RawEvent> events = retry.call(sourceId, source::poll);
            int stored = 0;
            int duplicates = 0;
            for (RawEvent event : events) {
                if (store.saveIfNew(sourceId, event)) {
                    stored++;
                } else {
                    duplicates++;
                }
            }
            log.info("ingestion source '{}': {} new, {} duplicate(s)", sourceId, stored, duplicates);
            metrics.recordPollSuccess(sourceId, stored, duplicates);
            return stored;
        } catch (Exception ex) {
            // Isolate the failure: log it and let the other sources (and the next cycle) proceed.
            log.error("ingestion source '{}' failed after retries: {}", sourceId, ex.toString());
            metrics.recordPollFailure(sourceId);
            return 0;
        }
    }
}
