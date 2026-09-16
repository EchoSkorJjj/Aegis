package com.aegis.core.ingestion;

import java.time.Instant;
import java.util.List;
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

    @Test
    void queuesStoredEventsForNormalisationUntilTheyAreMarkedProcessed() {
        String source = "nvd-" + UUID.randomUUID();
        store.saveIfNew(source, event("CVE-" + UUID.randomUUID(), "{\"a\":1}"));
        store.saveIfNew(source, event("CVE-" + UUID.randomUUID(), "{\"a\":2}"));

        List<StoredRawEvent> pending = store.findUnprocessed(source, 10);
        assertThat(pending).hasSize(2);
        assertThat(pending.get(0).sourceId()).isEqualTo(source);
        assertThat(pending.get(0).payload()).isNotBlank();

        store.markProcessed(pending.get(0).id());

        assertThat(store.findUnprocessed(source, 10))
                .extracting(StoredRawEvent::id)
                .containsExactly(pending.get(1).id());
    }

    @Test
    void findUnprocessedHonoursTheBatchLimit() {
        String source = "nvd-" + UUID.randomUUID();
        store.saveIfNew(source, event("CVE-" + UUID.randomUUID(), "{}"));
        store.saveIfNew(source, event("CVE-" + UUID.randomUUID(), "{}"));
        store.saveIfNew(source, event("CVE-" + UUID.randomUUID(), "{}"));

        assertThat(store.findUnprocessed(source, 2)).hasSize(2);
    }

    @Test
    void reportsTheNewestOccurredAtAsTheFeedWatermark() {
        String source = "nvd-" + UUID.randomUUID();
        Instant older = Instant.parse("2024-05-01T10:00:00Z");
        Instant newer = Instant.parse("2024-06-02T09:30:00Z");

        assertThat(store.latestOccurredAt(source)).isEmpty();

        store.saveIfNew(source, new RawEvent("CVE-" + UUID.randomUUID(), "application/json", "{}", older));
        store.saveIfNew(source, new RawEvent("CVE-" + UUID.randomUUID(), "application/json", "{}", newer));

        assertThat(store.latestOccurredAt(source)).contains(newer);
    }

    private long rowCount(String sourceId) {
        return jdbc.queryForObject(
                "select count(*) from core.raw_events where source_id = ?", Long.class, sourceId);
    }
}
