package com.aegis.core.ingestion;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns captured raw events into domain records: reads a feed's unprocessed events in batches, hands
 * each to the feed's own handler, and marks it processed either way.
 *
 * <p>Separating this from polling is what makes ingestion restartable. Fetching and parsing fail for
 * different reasons, and a payload already captured is never lost to a parser bug — fix the parser,
 * clear {@code processed_at}, and the events replay.
 *
 * <p>An event the handler cannot parse is still marked processed. The alternative is a poison pill
 * that blocks the queue on every run, and the raw payload remains in the store for inspection.
 */
@Component
public class RawEventDrainer {

    /**
     * Batches one run will drain before yielding. A first poll backfills thousands of records, so
     * stopping after a single batch would leave the queue growing faster than it drains; this bounds
     * the work per run without letting a backlog persist for many cycles.
     */
    private static final int MAX_BATCHES_PER_RUN = 50;

    private static final Logger log = LoggerFactory.getLogger(RawEventDrainer.class);

    private final RawEventStore rawEvents;

    public RawEventDrainer(RawEventStore rawEvents) {
        this.rawEvents = rawEvents;
    }

    /** What a feed does with one captured payload. Throwing marks the event processed and moves on. */
    @FunctionalInterface
    public interface RawEventHandler {
        void handle(StoredRawEvent event) throws Exception;
    }

    /**
     * Drain {@code sourceId}'s pending events, a batch at a time, until the queue is empty or the
     * per-run batch cap is reached.
     *
     * @return how many events the handler accepted without throwing
     */
    public int drain(String sourceId, int batchSize, RawEventHandler handler) {
        int handled = 0;
        int failed = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<StoredRawEvent> pending = rawEvents.findUnprocessed(sourceId, batchSize);
            if (pending.isEmpty()) {
                break;
            }
            for (StoredRawEvent event : pending) {
                try {
                    handler.handle(event);
                    handled++;
                } catch (Exception ex) {
                    failed++;
                    log.warn("{}: could not normalise raw event {} ({}): {}",
                            sourceId, event.id(), event.externalId(), ex.toString());
                } finally {
                    rawEvents.markProcessed(event.id());
                }
            }
            if (pending.size() < batchSize) {
                break;
            }
        }
        if (handled > 0 || failed > 0) {
            log.info("{}: normalised {} record(s), {} unparseable", sourceId, handled, failed);
        }
        return handled;
    }
}
