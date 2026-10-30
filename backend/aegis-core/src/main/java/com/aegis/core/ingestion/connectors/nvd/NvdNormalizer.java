package com.aegis.core.ingestion.connectors.nvd;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.ingestion.RawEventDrainer;
import com.aegis.core.ingestion.UnparseableEventException;
import com.aegis.core.platform.scheduling.SchedulerLease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains unprocessed NVD raw events into normalised advisories. The batching, failure isolation and
 * processed-marking all live in {@link RawEventDrainer}; this only says what an NVD payload becomes.
 */
@Component
@ConditionalOnProperty(name = "aegis.ingestion.nvd.enabled", havingValue = "true")
class NvdNormalizer {

    private static final Logger log = LoggerFactory.getLogger(NvdNormalizer.class);

    private final RawEventDrainer drainer;
    private final AdvisoryStore advisories;
    private final NvdAdvisoryParser parser;
    private final int batchSize;
    private final SchedulerLease lease;

    NvdNormalizer(
            RawEventDrainer drainer,
            AdvisoryStore advisories,
            NvdAdvisoryParser parser,
            NvdProperties properties,
            SchedulerLease lease) {
        this.drainer = drainer;
        this.advisories = advisories;
        this.parser = parser;
        this.batchSize = properties.normalizeBatchSize();
        this.lease = lease;
    }

    @Scheduled(
            fixedDelayString = "${aegis.ingestion.nvd.normalize-interval:PT1M}",
            initialDelayString = "${aegis.ingestion.nvd.normalize-interval:PT1M}")
    void normalizePending() {
        if (lease.held()) {
            drain();
        }
    }

    /**
     * Normalise the pending NVD events. Returns how many advisories were written. Package-visible so
     * the drain can be tested without waiting on the scheduler.
     */
    int drain() {
        return drainer.drain(NvdSource.SOURCE_ID, batchSize, event -> {
            Advisory advisory;
            try {
                advisory = parser.parse(event.payload());
            } catch (RuntimeException parseError) {
                throw new UnparseableEventException(NvdSource.SOURCE_ID, event.externalId(), parseError);
            }
            Advisory saved = advisories.upsert(advisory);
            log.info("pipeline stage=ingest.record sourceId={} rawEventId={} changeSignalId={}",
                    saved.sourceId(), event.id(), saved.id());
        });
    }
}
