package com.aegis.core.ingestion;

/**
 * Persistence port for the raw-event store: append-only and de-duplicating. Later stories read from
 * this store to normalise raw events into {@code Change}/{@code Signal} records.
 */
public interface RawEventStore {

    /**
     * Store {@code event} under {@code sourceId} unless a matching event was already ingested (same
     * feed and same external id, or same feed and identical payload when the feed has no id).
     *
     * @return {@code true} if newly stored, {@code false} if it was a duplicate
     */
    boolean saveIfNew(String sourceId, RawEvent event);
}
