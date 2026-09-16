package com.aegis.core.ingestion.nvd;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * NVD timestamps are extended ISO-8601 without a zone ({@code 2021-12-10T10:15:09.143}) and are UTC.
 * Some fields have carried an explicit offset historically, so both forms are accepted.
 */
final class NvdTimestamps {

    /**
     * Always the full extended form. {@code LocalDateTime.toString()} would drop zero seconds and
     * milliseconds ({@code 2024-01-01T00:00}), which NVD does not accept as a date filter.
     */
    private static final DateTimeFormatter QUERY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private NvdTimestamps() {
    }

    /** Null for a null, blank, or unparseable value — a missing timestamp never fails an ingest. */
    static Instant parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException withoutOffset) {
            try {
                return LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException unparseable) {
                return null;
            }
        }
    }

    /** The query format NVD expects for {@code lastModStartDate} / {@code lastModEndDate} (UTC). */
    static String format(Instant instant) {
        return QUERY_FORMAT.format(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }
}
