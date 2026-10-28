package com.aegis.core.ingestion.connectors.osv;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.aegis.core.ingestion.IngestionFeedMetrics;
import com.aegis.core.ingestion.RawEvent;
import com.aegis.core.ingestion.RawEventStore;
import com.aegis.core.platform.scheduling.SchedulerLease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Captures OSV advisories for the configured ecosystems.
 *
 * <p>Unlike the windowed feeds, OSV ships a full per-ecosystem <em>bulk snapshot</em> (a
 * multi-hundred-MB zip each), so it deliberately does <strong>not</strong> implement
 * {@code IngestionSource}: the generic {@code IngestionScheduler} polls every source on one shared
 * cadence ({@code poll-interval}, 15 min) on one loop, and re-downloading gigabytes that often — while
 * monopolising a scheduler thread for the whole download — would be wrong on both counts. Instead this
 * <em>self-schedules</em> on its own slow cadence (daily by default) and <em>streams</em> each record
 * straight into the (idempotent, de-duplicating) raw-event store — capping only single-record size,
 * never the count, so no package is silently dropped. The download runs on a scheduler pool thread
 * ({@code spring.task.scheduling.pool.size}); the normalisers run on the others, so a long sync never
 * starves them.
 *
 * <p>A record's raw-event id is {@code {osvId}@{content-fingerprint}}, so a revised record re-normalises
 * while an unchanged one de-duplicates on re-download — the same revision handling NVD gets from
 * {@code CVE@lastModified}. The normalised advisory keys on the bare OSV id, so a revision updates it.
 */
@Component
@ConditionalOnProperty(name = "aegis.ingestion.osv.enabled", havingValue = "true")
class OsvSource {

    static final String SOURCE_ID = "osv";

    private static final Logger log = LoggerFactory.getLogger(OsvSource.class);

    private final OsvClient client;
    private final RawEventStore store;
    private final OsvProperties properties;
    private final SchedulerLease lease;
    private final IngestionFeedMetrics metrics;
    private final Duration pollInterval;
    private final Clock clock;
    private Instant nextSyncAt = Instant.MIN;

    @Autowired
    OsvSource(OsvClient client, RawEventStore store, OsvProperties properties, SchedulerLease lease,
            IngestionFeedMetrics metrics, @Value("${aegis.ingestion.osv.poll-interval:P1D}") Duration pollInterval) {
        this(client, store, properties, lease, metrics, pollInterval, Clock.systemUTC());
    }

    OsvSource(OsvClient client, RawEventStore store, OsvProperties properties, SchedulerLease lease,
            IngestionFeedMetrics metrics, Duration pollInterval, Clock clock) {
        this.client = client;
        this.store = store;
        this.properties = properties;
        this.lease = lease;
        this.metrics = metrics;
        this.pollInterval = pollInterval;
        this.clock = clock;
        metrics.registerSource(SOURCE_ID, pollInterval);
    }

    /**
     * Downloads each configured ecosystem's snapshot on the OSV cadence (daily by default), after a
     * short initial delay so startup is unaffected. A failure is logged and isolated — the next run
     * retries, and unchanged records de-duplicate, so nothing is lost. Only the instance holding the
     * {@link SchedulerLease} syncs. Recheck on the lease-renewal cadence, so missing the lease at
     * startup or taking it over later does not postpone the first sync for a whole day.
     */
    @Scheduled(
            fixedDelayString = "${aegis.scheduling.lease-renewal:PT30S}",
            initialDelayString = "${aegis.ingestion.osv.initial-delay:PT30S}")
    void scheduledSync() {
        if (Instant.now(clock).isBefore(nextSyncAt) || !lease.held()) {
            return;
        }
        try {
            sync();
        } catch (Exception ex) {
            metrics.recordPollFailure(SOURCE_ID);
            log.error("osv: sync failed: {}", ex.toString());
        } finally {
            nextSyncAt = Instant.now(clock).plus(pollInterval);
        }
    }

    /** Stream every configured ecosystem's bulk export into the raw-event store; returns new events stored. */
    int sync() throws Exception {
        boolean includeMalicious = properties.includeMalicious();
        int stored = 0;
        int duplicates = 0;
        int skipped = 0;
        for (String ecosystem : properties.ecosystems()) {
            int[] counts = new int[3]; // [new, duplicate, skipped]
            client.forEachRecord(
                    ecosystem, properties.maxRecordsPerEcosystem(), properties.maxPayloadBytes(),
                    (id, json) -> {
                        // OSV's MAL-* records are its malicious-package database (typo-squats, supply-chain
                        // malware), not vulnerability advisories — they dominate some ecosystems and never
                        // match a curated inventory, so drop them unless the estate explicitly opts in.
                        if (!includeMalicious && id.startsWith("MAL-")) {
                            counts[2]++;
                            return;
                        }
                        RawEvent event = new RawEvent(
                                id + "@" + fingerprint(json), "application/json", json, null);
                        if (store.saveIfNew(SOURCE_ID, event)) {
                            counts[0]++;
                        } else {
                            counts[1]++;
                        }
                    });
            stored += counts[0];
            duplicates += counts[1];
            skipped += counts[2];
            // Per-ecosystem line so operators can watch a long first sync make progress.
            log.info("osv[{}]: {} new, {} duplicate(s), {} malicious-package report(s) skipped",
                    ecosystem, counts[0], counts[1], counts[2]);
        }
        log.info("osv: {} new, {} duplicate(s), {} malicious-package report(s) skipped across {} ecosystem(s)",
                stored, duplicates, skipped, properties.ecosystems().size());
        metrics.recordPollSuccess(SOURCE_ID, stored, duplicates);
        return stored;
    }

    /** First 8 bytes of the payload's SHA-256, hex — enough to detect a revised record on re-download. */
    private static String fingerprint(String json) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int index = 0; index < 8; index++) {
                hex.append(Character.forDigit((hash[index] >> 4) & 0xf, 16));
                hex.append(Character.forDigit(hash[index] & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
