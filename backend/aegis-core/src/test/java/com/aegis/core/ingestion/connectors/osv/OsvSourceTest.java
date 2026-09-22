package com.aegis.core.ingestion.connectors.osv;

import java.util.List;

import com.aegis.core.ingestion.RawEvent;
import com.aegis.core.ingestion.RawEventStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The OSV source streams a bulk export straight into the raw-event store; it is not a windowed feed.
 * These unit tests pin that contract: {@code sync()} stores as it streams, and each record's raw-event id
 * is keyed on the payload so a revision re-normalises while an unchanged record de-duplicates on re-download.
 */
class OsvSourceTest {

    private static OsvProperties properties(String... ecosystems) {
        return new OsvProperties(true, null, List.of(ecosystems), null, null, 0, 0, 0, false, false);
    }

    private static OsvProperties propertiesWithMalicious(String... ecosystems) {
        return new OsvProperties(true, null, List.of(ecosystems), null, null, 0, 0, 0, false, true);
    }

    @Test
    void streamsEachRecordIntoTheStoreAndCountsNewOnes() throws Exception {
        RawEventStore store = mock(RawEventStore.class);
        when(store.saveIfNew(eq(OsvSource.SOURCE_ID), any())).thenReturn(true, false);
        OsvClient client = (ecosystem, maxRecords, maxRecordBytes, consumer) -> {
            consumer.accept("GHSA-aaa", "{\"id\":\"GHSA-aaa\"}");
            consumer.accept("GHSA-bbb", "{\"id\":\"GHSA-bbb\"}");
        };

        int stored = new OsvSource(client, store, properties("npm")).sync();

        // Records go straight to the store as they stream; the count reflects only the new (not duplicate) ones.
        assertThat(stored).isEqualTo(1); // first saveIfNew -> true (new), second -> false (duplicate)

        ArgumentCaptor<RawEvent> captor = ArgumentCaptor.forClass(RawEvent.class);
        verify(store, times(2)).saveIfNew(eq(OsvSource.SOURCE_ID), captor.capture());
        List<RawEvent> saved = captor.getAllValues();
        assertThat(saved).extracting(RawEvent::externalId)
                .allSatisfy(id -> assertThat(id).contains("@")); // {osvId}@{fingerprint}
        assertThat(saved.get(0).externalId()).startsWith("GHSA-aaa@");
        assertThat(saved.get(1).externalId()).startsWith("GHSA-bbb@");
        assertThat(saved).extracting(RawEvent::contentType).containsOnly("application/json");
    }

    @Test
    void keysTheRawEventOnContentSoRevisionsReNormaliseButUnchangedRecordsRepeatTheirId() throws Exception {
        RawEventStore store = mock(RawEventStore.class);
        when(store.saveIfNew(eq(OsvSource.SOURCE_ID), any())).thenReturn(true);
        // The same OSV id seen twice unchanged, then revised — the store keys de-duplication on externalId.
        OsvClient client = (ecosystem, maxRecords, maxRecordBytes, consumer) -> {
            consumer.accept("GHSA-aaa", "{\"modified\":\"2099-01-01\"}");
            consumer.accept("GHSA-aaa", "{\"modified\":\"2099-01-01\"}");
            consumer.accept("GHSA-aaa", "{\"modified\":\"2099-02-02\"}");
        };

        new OsvSource(client, store, properties("npm")).sync();

        ArgumentCaptor<RawEvent> captor = ArgumentCaptor.forClass(RawEvent.class);
        verify(store, times(3)).saveIfNew(eq(OsvSource.SOURCE_ID), captor.capture());
        List<String> ids = captor.getAllValues().stream().map(RawEvent::externalId).toList();
        assertThat(ids).allSatisfy(id -> assertThat(id).startsWith("GHSA-aaa@"));
        assertThat(ids.get(0)).isEqualTo(ids.get(1));      // unchanged content → identical id → dedup
        assertThat(ids.get(2)).isNotEqualTo(ids.get(0));   // revised content → new id → re-normalise
    }

    @Test
    void skipsMaliciousPackageReportsByDefaultButKeepsRealAdvisories() throws Exception {
        RawEventStore store = mock(RawEventStore.class);
        when(store.saveIfNew(eq(OsvSource.SOURCE_ID), any())).thenReturn(true);
        OsvClient client = (ecosystem, maxRecords, maxRecordBytes, consumer) -> {
            consumer.accept("MAL-2025-1", "{\"id\":\"MAL-2025-1\"}"); // OSV malicious-package report
            consumer.accept("GHSA-aaa", "{\"id\":\"GHSA-aaa\"}");      // real advisory
            consumer.accept("CVE-2025-1", "{\"id\":\"CVE-2025-1\"}");  // real advisory
        };

        int stored = new OsvSource(client, store, properties("npm")).sync();

        // Only the two real advisories are stored; the MAL-* report never reaches the store.
        assertThat(stored).isEqualTo(2);
        ArgumentCaptor<RawEvent> captor = ArgumentCaptor.forClass(RawEvent.class);
        verify(store, times(2)).saveIfNew(eq(OsvSource.SOURCE_ID), captor.capture());
        assertThat(captor.getAllValues()).extracting(RawEvent::externalId)
                .noneMatch(id -> id.startsWith("MAL-"));
    }

    @Test
    void includesMaliciousPackageReportsWhenOptedIn() throws Exception {
        RawEventStore store = mock(RawEventStore.class);
        when(store.saveIfNew(eq(OsvSource.SOURCE_ID), any())).thenReturn(true);
        OsvClient client = (ecosystem, maxRecords, maxRecordBytes, consumer) ->
                consumer.accept("MAL-2025-1", "{\"id\":\"MAL-2025-1\"}");

        int stored = new OsvSource(client, store, propertiesWithMalicious("npm")).sync();

        assertThat(stored).isEqualTo(1); // opted in — the malicious-package report is stored
        verify(store, times(1)).saveIfNew(eq(OsvSource.SOURCE_ID), any());
    }

    @Test
    void iteratesEveryConfiguredEcosystem() throws Exception {
        RawEventStore store = mock(RawEventStore.class);
        when(store.saveIfNew(eq(OsvSource.SOURCE_ID), any())).thenReturn(true);
        OsvClient client = (ecosystem, maxRecords, maxRecordBytes, consumer) ->
                consumer.accept("REC-" + ecosystem, "{\"ecosystem\":\"" + ecosystem + "\"}");

        new OsvSource(client, store, properties("npm", "PyPI", "crates.io")).sync();

        ArgumentCaptor<RawEvent> captor = ArgumentCaptor.forClass(RawEvent.class);
        verify(store, times(3)).saveIfNew(eq(OsvSource.SOURCE_ID), captor.capture());
        assertThat(captor.getAllValues()).extracting(RawEvent::externalId)
                .anySatisfy(id -> assertThat(id).startsWith("REC-npm@"))
                .anySatisfy(id -> assertThat(id).startsWith("REC-PyPI@"))
                .anySatisfy(id -> assertThat(id).startsWith("REC-crates.io@"));
    }
}
