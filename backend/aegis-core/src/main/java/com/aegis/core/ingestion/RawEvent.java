package com.aegis.core.ingestion;

import java.time.Instant;

/**
 * One raw item fetched from a feed, before any normalisation.
 *
 * <ul>
 *   <li>{@code externalId} — the feed's own identifier (e.g. {@code "CVE-2024-1234"}). When present it
 *       is what de-duplication keys on; when absent the framework keys on a hash of {@code payload}.</li>
 *   <li>{@code contentType} — media type of {@code payload}; defaults to {@code application/json}.</li>
 *   <li>{@code payload} — the raw body exactly as received (required).</li>
 *   <li>{@code occurredAt} — when the event happened at the source, if the feed reports it (nullable).</li>
 * </ul>
 *
 * <p>Storage identity (the internal id) and the ingest timestamp are owned by the framework, not the
 * connector.
 */
public record RawEvent(String externalId, String contentType, String payload, Instant occurredAt) {

    public RawEvent {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("RawEvent payload must not be blank");
        }
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/json";
        }
    }
}
