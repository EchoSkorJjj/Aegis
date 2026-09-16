package com.aegis.core.ingestion.vendorstatus;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Status feeds date things three different ways: Statuspage emits ISO-8601
 * ({@code 2026-09-16T02:03:10.000Z}), RSS uses RFC 1123 with a numeric offset
 * ({@code Tue, 15 Sep 2026 10:27:10 +0000}), and some publishers — AWS among them — use RFC 1123
 * with an alphabetic zone name instead ({@code Tue, 15 Sep 2026 03:27:10 PDT}).
 *
 * <p>That last form matters: {@link DateTimeFormatter#RFC_1123_DATE_TIME} accepts only {@code GMT} or
 * a numeric offset, so without the zone-name fallback every AWS incident would land with no timestamp.
 */
final class FeedTimestamps {

    /** RFC 1123 with a zone abbreviation ({@code PDT}, {@code EST}) rather than an offset. */
    private static final DateTimeFormatter RFC_1123_ZONE_NAME = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("EEE, d MMM yyyy HH:mm:ss ")
            .appendZoneText(TextStyle.SHORT)
            .toFormatter(Locale.ENGLISH);

    private FeedTimestamps() {
    }

    /** Null for a null, blank, or unparseable value — a missing timestamp never fails an ingest. */
    static Instant parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException notIso) {
            return parseRfc1123(trimmed);
        }
    }

    private static Instant parseRfc1123(String value) {
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException notNumericOffset) {
            try {
                return ZonedDateTime.parse(value, RFC_1123_ZONE_NAME).toInstant();
            } catch (DateTimeParseException unparseable) {
                return null;
            }
        }
    }
}
