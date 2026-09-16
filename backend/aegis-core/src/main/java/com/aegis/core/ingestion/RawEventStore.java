package com.aegis.core.ingestion;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for the raw-event store: append-only and de-duplicating. Normalisers read from
 * this store to turn raw events into domain records (advisories today; {@code Change}/{@code Signal}
 * later), then mark each one processed so a restart resumes rather than re-parsing.
 */
public interface RawEventStore {

    /**
     * Store {@code event} under {@code sourceId} unless a matching event was already ingested (same
     * feed and same external id, or same feed and identical payload when the feed has no id).
     *
     * @return {@code true} if newly stored, {@code false} if it was a duplicate
     */
    boolean saveIfNew(String sourceId, RawEvent event);

    /** The oldest unprocessed events for one feed, up to {@code limit} — the normaliser's work queue. */
    List<StoredRawEvent> findUnprocessed(String sourceId, int limit);

    /** Record that normalisation has consumed this event. Idempotent; never rewrites the payload. */
    void markProcessed(UUID id);

    /**
     * The newest {@code occurredAt} stored for a feed, or empty when it has ingested nothing.
     * Connectors use this as their poll watermark, which keeps the cursor in the store rather than
     * in connector-owned state — so a restart resumes from what was actually persisted.
     */
    Optional<Instant> latestOccurredAt(String sourceId);
}
