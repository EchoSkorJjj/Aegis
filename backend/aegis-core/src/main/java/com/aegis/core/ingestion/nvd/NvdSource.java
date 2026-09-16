package com.aegis.core.ingestion.nvd;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.aegis.core.ingestion.IngestionSource;
import com.aegis.core.ingestion.RawEvent;
import com.aegis.core.ingestion.RawEventStore;
import com.aegis.core.ingestion.nvd.NvdApiClient.NvdCve;
import com.aegis.core.ingestion.nvd.NvdApiClient.NvdPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Ingestion connector for the NVD CVE API 2.0. Each poll asks for the CVEs modified since the last
 * one and hands the framework the raw JSON of each; normalisation into
 * {@link com.aegis.core.ingestion.advisory.Advisory} records is {@link NvdAdvisoryParser}'s job.
 *
 * <p><b>Watermark.</b> The poll window starts from the newest {@code occurredAt} in the raw-event
 * store — which this connector sets to the CVE's {@code lastModified} — minus a small overlap, so a
 * restart resumes from what was actually persisted rather than from connector-held state. Before
 * anything is stored the window is {@code initialWindow} wide.
 *
 * <p><b>Revisions.</b> NVD revises CVEs in place (rescored CVSS, re-scoped CPE ranges). The external
 * id is therefore {@code <cve-id>@<lastModified>}, so each revision is a distinct raw event rather
 * than a duplicate of the one already stored, and the raw store keeps the full revision history.
 */
@Component
@ConditionalOnProperty(name = "aegis.ingestion.nvd.enabled", havingValue = "true")
class NvdSource implements IngestionSource {

    static final String SOURCE_ID = "nvd";

    /** NVD rejects a {@code lastMod} range wider than 120 days. */
    private static final Duration MAX_WINDOW = Duration.ofDays(120);

    private static final Logger log = LoggerFactory.getLogger(NvdSource.class);

    private final NvdApiClient client;
    private final RawEventStore store;
    private final NvdProperties properties;

    NvdSource(NvdApiClient client, RawEventStore store, NvdProperties properties) {
        this.client = client;
        this.store = store;
        this.properties = properties;
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<RawEvent> poll() throws Exception {
        Instant end = Instant.now();
        Instant start = windowStart(end);

        List<RawEvent> events = new ArrayList<>();
        int startIndex = 0;
        int totalResults = 0;
        for (int page = 0; page < properties.maxPages(); page++) {
            if (page > 0) {
                pause(properties.pageDelay());
            }
            NvdPage result = client.fetch(start, end, startIndex);
            totalResults = result.totalResults();
            if (result.cves().isEmpty()) {
                break;
            }
            for (NvdCve cve : result.cves()) {
                RawEvent event = toRawEvent(cve);
                if (event != null) {
                    events.add(event);
                }
            }
            startIndex += result.cves().size();
            if (startIndex >= totalResults) {
                break;
            }
        }

        if (startIndex < totalResults) {
            // The watermark advances to what was fetched, so the rest of this window would be skipped.
            // Shorten aegis.ingestion.nvd.initial-window or raise max-pages to close the gap.
            log.warn("nvd: fetched {} of {} CVEs modified between {} and {} — page cap ({}) reached",
                    startIndex, totalResults, start, end, properties.maxPages());
        }
        log.debug("nvd: {} event(s) from window {} .. {}", events.size(), start, end);
        return events;
    }

    private Instant windowStart(Instant end) {
        Instant start = store.latestOccurredAt(SOURCE_ID)
                .map(watermark -> watermark.minus(properties.overlap()))
                .orElseGet(() -> end.minus(properties.initialWindow()));
        Instant earliest = end.minus(MAX_WINDOW);
        return start.isBefore(earliest) ? earliest : start;
    }

    /** Null when the CVE is unusable — no id, or a payload past the configured cap. */
    private RawEvent toRawEvent(NvdCve cve) {
        int bytes = cve.json().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > properties.maxPayloadBytes()) {
            log.warn("nvd: skipping {} — payload {} bytes exceeds the {} byte cap",
                    cve.id(), bytes, properties.maxPayloadBytes());
            return null;
        }
        String externalId = cve.lastModifiedRaw() == null ? cve.id() : cve.id() + "@" + cve.lastModifiedRaw();
        return new RawEvent(externalId, "application/json", cve.json(), cve.lastModified());
    }

    private static void pause(Duration delay) {
        if (delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("nvd poll interrupted", ex);
        }
    }
}
