package com.aegis.core.signals;

import java.time.Instant;
import java.util.UUID;

/**
 * A unified view of "a real-world change ingested from any source" — a vulnerability advisory, a
 * vendor outage, or a security-news article — regardless of which connector produced it.
 *
 * <p>This is a read-only projection, not a fourth source of truth: it is backed by the
 * {@code change_signals} database view over {@code advisories}, {@code vendor_incidents}, and
 * {@code news_items} (see {@code V10__change_signals_view.sql}), each of which is already normalised
 * and idempotently upserted by its own connector on {@code (sourceId, externalId)}. {@code id} is
 * that origin row's own id, so a signal's origin table follows from {@link #signalType()}.
 */
public record ChangeSignal(
        UUID id,
        SignalType signalType,
        String sourceId,
        String externalId,
        String title,
        String summary,
        String severity,
        String url,
        Instant occurredAt,
        Instant createdAt,
        Instant updatedAt) {

    /** Which of the three normalised ingestion outputs this signal was projected from. */
    public enum SignalType {
        /** Projected from {@code advisories} (the NVD CVE connector today). */
        VULNERABILITY,
        /** Projected from {@code vendor_incidents} (the vendor-status connector). */
        VENDOR_OUTAGE,
        /** Projected from {@code news_items} (the security-news connector). */
        SECURITY_NEWS
    }
}
