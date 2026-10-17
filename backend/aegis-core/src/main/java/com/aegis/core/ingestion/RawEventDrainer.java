package com.aegis.core.ingestion;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns captured raw events into domain records: reads a feed's unprocessed events in batches, hands
 * each to the feed's own handler, and decides what to do with the ones that fail.
 *
 * <p>Separating this from polling is what makes ingestion restartable. Fetching and parsing fail for
 * different reasons, and a payload already captured is never lost to a parser bug — fix the parser,
 * clear {@code processed_at}, and the events replay.
 *
 * <p><b>Terminal vs. transient failures.</b> A handler throws {@link UnparseableEventException} for a
 * payload that is definitively malformed: the event is dropped immediately (marked processed; the raw
 * payload stays in the store for inspection), so a poison pill never blocks the queue. Any other
 * exception is treated as transient (a DB blip, a lost upsert race): the event's attempt count is
 * advanced and it is left queued to be retried on a later run, and only dropped once it has failed
 * {@code normalizeMaxAttempts} times. Within a single run each event is attempted at most once — a
 * still-queued transient failure is skipped rather than re-fetched — so the drain advances past it
 * instead of spinning on the head of the queue.
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
    private final int maxAttempts;
    private final IngestionFeedMetrics metrics;

    public RawEventDrainer(RawEventStore rawEvents, IngestionProperties properties, IngestionFeedMetrics metrics) {
        this.rawEvents = rawEvents;
        this.maxAttempts = properties.normalizeMaxAttempts();
        this.metrics = metrics;
    }

    /** What a feed does with one captured payload. Throwing marks the event for retry or drops it. */
    @FunctionalInterface
    public interface RawEventHandler {
        void handle(StoredRawEvent event) throws Exception;
    }

    /**
     * Drain {@code sourceId}'s pending events, a batch at a time, until the queue is empty (of events
     * not already tried this run) or the per-run batch cap is reached.
     *
     * @return how many events the handler accepted without throwing
     */
    public int drain(String sourceId, int batchSize, RawEventHandler handler) {
        int handled = 0;
        int dropped = 0;
        int deferred = 0;
        // A transient failure leaves its event queued at the head; skip anything already tried this run
        // so the drain moves past it rather than re-fetching it every batch.
        Set<UUID> triedThisRun = new HashSet<>();
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<StoredRawEvent> pending = rawEvents.findUnprocessed(sourceId, batchSize);
            if (pending.isEmpty()) {
                break;
            }
            boolean progressed = false;
            for (StoredRawEvent event : pending) {
                if (!triedThisRun.add(event.id())) {
                    continue; // already attempted this run (a deferred transient failure)
                }
                progressed = true;
                try {
                    handler.handle(event);
                    handled++;
                    rawEvents.markProcessed(event.id());
                } catch (UnparseableEventException terminal) {
                    dropped++;
                    log.warn("{}: dropping unparseable raw event {} ({}): {}",
                            sourceId, event.id(), event.externalId(), terminal.toString());
                    rawEvents.markProcessed(event.id());
                } catch (Exception transientError) {
                    rawEvents.recordFailure(event.id());
                    int attempts = event.attempts() + 1;
                    if (attempts >= maxAttempts) {
                        dropped++;
                        log.warn("{}: giving up on raw event {} ({}) after {} attempts: {}",
                                sourceId, event.id(), event.externalId(), attempts, transientError.toString());
                        rawEvents.markProcessed(event.id());
                    } else {
                        deferred++;
                        log.warn("{}: raw event {} ({}) failed normalisation (attempt {}), will retry: {}",
                                sourceId, event.id(), event.externalId(), attempts, transientError.toString());
                    }
                }
            }
            // No unseen events in this batch (only deferred failures remain), or the feed is drained.
            if (!progressed || pending.size() < batchSize) {
                break;
            }
        }
        if (handled > 0 || dropped > 0 || deferred > 0) {
            log.info("{}: normalised {} record(s), {} dropped, {} deferred for retry",
                    sourceId, handled, dropped, deferred);
        }
        metrics.recordNormalizeOutcome(sourceId, handled, dropped, deferred);
        return handled;
    }
}
