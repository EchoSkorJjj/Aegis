package com.aegis.core.ingestion.connectors.feed;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeedTimestampsTest {

    @Test
    void parsesStatuspageIso8601() {
        assertThat(FeedTimestamps.parse("2026-09-16T02:03:10.000Z"))
                .isEqualTo(Instant.parse("2026-09-16T02:03:10Z"));
        assertThat(FeedTimestamps.parse("2026-09-01T10:00:00.000-07:00"))
                .isEqualTo(Instant.parse("2026-09-01T17:00:00Z"));
    }

    @Test
    void parsesRfc1123WithANumericOffset() {
        assertThat(FeedTimestamps.parse("Mon, 14 Sep 2026 11:05:00 GMT"))
                .isEqualTo(Instant.parse("2026-09-14T11:05:00Z"));
        assertThat(FeedTimestamps.parse("Mon, 14 Sep 2026 11:05:00 +0200"))
                .isEqualTo(Instant.parse("2026-09-14T09:05:00Z"));
    }

    @Test
    void parsesRfc1123WithAZoneAbbreviationAsAwsPublishesIt() {
        // RFC_1123_DATE_TIME alone rejects "PDT", which would leave every AWS incident undated.
        assertThat(FeedTimestamps.parse("Tue, 15 Sep 2026 03:27:10 PDT"))
                .isEqualTo(Instant.parse("2026-09-15T10:27:10Z"));
    }

    @Test
    void returnsNullRatherThanThrowingOnAnythingElse() {
        assertThat(FeedTimestamps.parse(null)).isNull();
        assertThat(FeedTimestamps.parse("   ")).isNull();
        assertThat(FeedTimestamps.parse("last Tuesday")).isNull();
    }
}
