package com.aegis.core.ingestion.connectors.osv;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.ingestion.RawEventDrainer;
import com.aegis.core.ingestion.UnparseableEventException;
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

    private final RawEventDrainer drainer;
    private final AdvisoryStore advisories;
    private final OsvAdvisoryParser parser;
    private final int batchSize;

    OsvNormalizer(
            RawEventDrainer drainer,
            AdvisoryStore advisories,
            OsvAdvisoryParser parser,
            OsvProperties properties) {
        this.drainer = drainer;
        this.advisories = advisories;
        this.parser = parser;
        this.batchSize = properties.normalizeBatchSize();
    }

    @Scheduled(
            fixedDelayString = "${aegis.ingestion.osv.normalize-interval:PT5M}",
            initialDelayString = "${aegis.ingestion.osv.normalize-interval:PT5M}")
    void normalizePending() {
        drain();
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
            advisories.upsert(advisory);
        });
    }
}
