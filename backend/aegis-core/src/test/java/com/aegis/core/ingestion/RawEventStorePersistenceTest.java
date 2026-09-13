package com.aegis.core.ingestion;

import java.time.Instant;
import java.util.UUID;

import com.aegis.core.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class RawEventStorePersistenceTest extends AbstractIntegrationTest {

    @Autowired
    private RawEventStore store;

    @Autowired
    private JdbcTemplate jdbc;

    private static RawEvent event(String externalId, String payload) {
        return new RawEvent(externalId, "application/json", payload, Instant.now());
    }

    @Test
    void storesNewEventThenDeduplicatesByExternalIdWithinASource() {
        String source = "nvd-" + UUID.randomUUID();
        String externalId = "CVE-" + UUID.randomUUID();

        assertThat(store.saveIfNew(source, event(externalId, "{\"a\":1}"))).isTrue();
        // Same source + external id is a duplicate even if the payload differs.
        assertThat(store.saveIfNew(source, event(externalId, "{\"a\":2}"))).isFalse();

        assertThat(rowCount(source)).isEqualTo(1);
    }

    @Test
    void sameExternalIdInDifferentSourcesAreDistinct() {
        String externalId = "CVE-" + UUID.randomUUID();
        String feedA = "feed-a-" + UUID.randomUUID();
        String feedB = "feed-b-" + UUID.randomUUID();

        assertThat(store.saveIfNew(feedA, event(externalId, "{}"))).isTrue();
        assertThat(store.saveIfNew(feedB, event(externalId, "{}"))).isTrue();

        assertThat(rowCount(feedA)).isEqualTo(1);
        assertThat(rowCount(feedB)).isEqualTo(1);
    }

    @Test
    void withoutAnExternalIdDeduplicatesByPayloadHash() {
        String source = "news-" + UUID.randomUUID();
        String payload = "{\"headline\":\"" + UUID.randomUUID() + "\"}";

        assertThat(store.saveIfNew(source, event(null, payload))).isTrue();
        assertThat(store.saveIfNew(source, event(null, payload))).isFalse(); // identical payload → duplicate
        assertThat(store.saveIfNew(source, event(null, payload + " "))).isTrue(); // different payload → new

        assertThat(rowCount(source)).isEqualTo(2);
    }

    private long rowCount(String sourceId) {
        return jdbc.queryForObject(
                "select count(*) from core.raw_events where source_id = ?", Long.class, sourceId);
    }
}
