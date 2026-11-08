package com.aegis.core.ingestion.connectors.osv;

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
 * Drains unprocessed OSV raw events into normalised advisories. Batching, failure isolation, and
 * processed-marking live in {@link RawEventDrainer}; this only says what an OSV payload becomes.
 */
@Component
@ConditionalOnProperty(name = "aegis.ingestion.osv.enabled", havingValue = "true")
class OsvNormalizer {

    private static final Logger log = LoggerFactory.getLogger(OsvNormalizer.class);

    private final RawEventDrainer drainer;
    private final AdvisoryStore advisories;
    private final OsvAdvisoryParser parser;
    private final int batchSize;
    private final SchedulerLease lease;

    OsvNormalizer(
            RawEventDrainer drainer,
            AdvisoryStore advisories,
            OsvAdvisoryParser parser,
            OsvProperties properties,
            SchedulerLease lease) {
        this.drainer = drainer;
        this.advisories = advisories;
        this.parser = parser;
        this.batchSize = properties.normalizeBatchSize();
        this.lease = lease;
    }

    @Scheduled(
            fixedDelayString = "${aegis.ingestion.osv.normalize-interval:PT5M}",
            initialDelayString = "${aegis.ingestion.osv.normalize-interval:PT5M}")
    void normalizePending() {
        if (lease.held()) {
            drain();
        }
    }

    /** Normalise pending OSV events; returns how many advisories were written. Package-visible for tests. */
    int drain() {
        return drainer.drain(OsvSource.SOURCE_ID, batchSize, event -> {
            Advisory advisory;
            try {
                advisory = parser.parse(event.payload());
            } catch (RuntimeException parseError) {
                throw new UnparseableEventException(OsvSource.SOURCE_ID, event.externalId(), parseError);
            }
            Advisory saved = advisories.upsert(advisory);
            log.info("pipeline stage=ingest.record sourceId={} rawEventId={} changeSignalId={}",
                    saved.sourceId(), event.id(), saved.id());
        });
    }
}
