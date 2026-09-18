package com.aegis.core.ingestion.signal;

import java.time.Instant;

import com.aegis.core.ingestion.signal.ChangeSignal.SignalType;

/**
 * Optional filters for the unified change-signals feed. Every field is nullable/blank-tolerant — a
 * null or blank value imposes no constraint on that dimension. {@code from}/{@code to} bound
 * {@code occurredAt}; {@code search} matches the title or the feed identifier.
 */
public record ChangeSignalFilter(
        SignalType type, String severity, String source, String search, Instant from, Instant to) {

    public static final ChangeSignalFilter NONE = new ChangeSignalFilter(null, null, null, null, null, null);
}
