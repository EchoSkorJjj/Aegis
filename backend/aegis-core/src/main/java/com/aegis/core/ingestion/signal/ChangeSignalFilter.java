package com.aegis.core.ingestion.signal;

import java.time.Instant;

import com.aegis.core.ingestion.signal.ChangeSignal.SignalType;

/**
 * Optional filters for the unified change-signals feed. Every field is nullable/blank-tolerant — a
 * null or blank value imposes no constraint on that dimension. {@code from}/{@code to} bound
 * {@code occurredAt}; {@code search} matches the title or the feed identifier; {@code ecosystem}
 * keeps only advisory signals that touch that package ecosystem (npm, PyPI, …), folded across every
 * advisory sharing the canonical id.
 */
public record ChangeSignalFilter(
        SignalType type, String severity, String source, String search, String ecosystem,
        Instant from, Instant to) {

    public static final ChangeSignalFilter NONE =
            new ChangeSignalFilter(null, null, null, null, null, null, null);
}
