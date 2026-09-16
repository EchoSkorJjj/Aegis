package com.aegis.core.ingestion;

import java.time.Instant;
import java.util.UUID;

/**
 * A {@link RawEvent} as it sits in the store: the connector's payload plus the identity and
 * timestamps the framework owns. Normalisers read these, turn them into domain records, and then
 * mark the id processed.
 */
public record StoredRawEvent(
        UUID id,
        String sourceId,
        String externalId,
        String contentType,
        String payload,
        Instant occurredAt,
        Instant ingestedAt) {
}
