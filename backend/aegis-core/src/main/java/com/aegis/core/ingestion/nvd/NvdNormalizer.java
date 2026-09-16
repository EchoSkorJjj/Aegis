package com.aegis.core.ingestion.nvd;

import java.util.List;

import com.aegis.core.ingestion.RawEventStore;
import com.aegis.core.ingestion.StoredRawEvent;
import com.aegis.core.ingestion.advisory.Advisory;
import com.aegis.core.ingestion.advisory.AdvisoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains unprocessed NVD raw events into normalised advisories.
 *
 * <p>Separating this from the poll is what makes ingestion restartable: fetching and parsing fail for
 * different reasons, and a payload already captured is never lost to a parser bug — fix the parser,
 * clear {@code processed_at}, and the events replay.
 *
 * <p>An event that cannot be parsed is still marked processed. The alternative is a poison pill that
 * blocks the queue on every run, and the raw payload remains in the store for inspection either way.
 */
@Component
@ConditionalOnProperty(name = "aegis.ingestion.nvd.enabled", havingValue = "true")
class NvdNormalizer {

    private static final Logger log = LoggerFactory.getLogger(NvdNormalizer.class);

    /**
     * Batches one run will drain before yielding. A first poll backfills thousands of CVEs, so
     * stopping after a single batch would leave the queue growing faster than it drains; this bounds
     * the work per run without letting a backlog persist for many cycles.
     */
    private static final int MAX_BATCHES_PER_RUN = 50;

    private final RawEventStore rawEvents;
    private final AdvisoryStore advisories;
    private final NvdAdvisoryParser parser;
    private final int batchSize;

    NvdNormalizer(
            RawEventStore rawEvents,
            AdvisoryStore advisories,
            NvdAdvisoryParser parser,
            NvdProperties properties) {
        this.rawEvents = rawEvents;
        this.advisories = advisories;
        this.parser = parser;
        this.batchSize = properties.normalizeBatchSize();
    }

    @Scheduled(
            fixedDelayString = "${aegis.ingestion.nvd.normalize-interval:PT1M}",
            initialDelayString = "${aegis.ingestion.nvd.normalize-interval:PT1M}")
    void normalizePending() {
        drain();
    }

    /**
     * Normalise pending events, a batch at a time, until the queue is empty or
     * {@link #MAX_BATCHES_PER_RUN} is reached. Returns how many advisories were written.
     * Package-visible so the drain can be tested without waiting on the scheduler.
     */
    int drain() {
        int normalized = 0;
        int failed = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<StoredRawEvent> pending = rawEvents.findUnprocessed(NvdSource.SOURCE_ID, batchSize);
            if (pending.isEmpty()) {
                break;
            }
            for (StoredRawEvent event : pending) {
                try {
                    Advisory advisory = parser.parse(event.payload());
                    advisories.upsert(advisory);
                    normalized++;
                } catch (Exception ex) {
                    failed++;
                    log.warn("nvd: could not normalise raw event {} ({}): {}",
                            event.id(), event.externalId(), ex.toString());
                } finally {
                    rawEvents.markProcessed(event.id());
                }
            }
            if (pending.size() < batchSize) {
                break;
            }
        }
        if (normalized > 0 || failed > 0) {
            log.info("nvd: normalised {} advisory/advisories ({} unparseable)", normalized, failed);
        }
        return normalized;
    }
}
