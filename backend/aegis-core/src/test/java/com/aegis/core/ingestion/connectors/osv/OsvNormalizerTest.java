package com.aegis.core.ingestion.connectors.osv;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.ingestion.RawEventDrainer;
import com.aegis.core.ingestion.StoredRawEvent;
import com.aegis.core.ingestion.UnparseableEventException;
import com.aegis.core.platform.scheduling.SchedulerLease;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** What an OSV raw event becomes: an advisory, or a dead event when its payload is unusable. */
class OsvNormalizerTest {

    private final RawEventDrainer drainer = mock(RawEventDrainer.class);
    private final AdvisoryStore advisories = mock(AdvisoryStore.class);
    private final SchedulerLease lease = mock(SchedulerLease.class);
    private OsvNormalizer normalizer;

    @BeforeEach
    void setUp() {
        OsvProperties properties = new OsvProperties(true, null, List.of("npm"), null, null, 0, 0, 25, false, false);
        normalizer = new OsvNormalizer(drainer, advisories, new OsvAdvisoryParser(new ObjectMapper()), properties, lease);
    }

    private RawEventDrainer.RawEventHandler handler() {
        when(drainer.drain(eq(OsvSource.SOURCE_ID), eq(25), any())).thenReturn(1);
        assertThat(normalizer.drain()).isEqualTo(1);
        ArgumentCaptor<RawEventDrainer.RawEventHandler> handler = ArgumentCaptor.captor();
        verify(drainer).drain(eq(OsvSource.SOURCE_ID), eq(25), handler.capture());
        return handler.getValue();
    }

    private static StoredRawEvent event(String payload) {
        return new StoredRawEvent(UUID.randomUUID(), OsvSource.SOURCE_ID, "GHSA-1", "application/json", payload,
                Instant.now(), Instant.now(), 0);
    }

    @Test
    void aValidRecordBecomesAnAdvisory() throws Exception {
        String payload = new ClassPathResource("osv/ghsa-npm-axios.json").getContentAsString(StandardCharsets.UTF_8);
        when(advisories.upsert(any())).thenAnswer(call -> call.getArgument(0));

        handler().handle(event(payload));

        ArgumentCaptor<Advisory> saved = ArgumentCaptor.captor();
        verify(advisories).upsert(saved.capture());
        assertThat(saved.getValue().canonicalId()).isEqualTo("CVE-2026-25639");
    }

    @Test
    void anUnusablePayloadIsMarkedUnparseableAndWritesNothing() {
        assertThatThrownBy(() -> handler().handle(event("not json")))
                .isInstanceOf(UnparseableEventException.class);

        verify(advisories, never()).upsert(any());
    }

    @Test
    void onlyTheLeaseHolderDrains() {
        when(lease.held()).thenReturn(false);
        normalizer.normalizePending();
        verifyNoInteractions(drainer);

        when(lease.held()).thenReturn(true);
        normalizer.normalizePending();
        verify(drainer).drain(eq(OsvSource.SOURCE_ID), eq(25), any());
    }
}
