package com.aegis.core.ingestion.nvd;

import com.aegis.core.ingestion.RawEventDrainer;
import com.aegis.core.ingestion.advisory.AdvisoryStore;
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

    private final RawEventDrainer drainer;
    private final AdvisoryStore advisories;
    private final NvdAdvisoryParser parser;
    private final int batchSize;

    NvdNormalizer(
            RawEventDrainer drainer,
            AdvisoryStore advisories,
            NvdAdvisoryParser parser,
            NvdProperties properties) {
        this.drainer = drainer;
        this.advisories = advisories;
        this.parser = parser;
        this.batchSize = properties.normalizeBatchSize();
    }

    @Scheduled(
            fixedDelayString = "${aegis.ingestion.nvd.normalize-interval:PT1M}",
            initialDelayString = "${aegis.ingestion.nvd.normalize-interval:PT1M}")
    void normalizePending() {
        drain();
    }

    /**
     * Normalise the pending NVD events. Returns how many advisories were written. Package-visible so
     * the drain can be tested without waiting on the scheduler.
     */
    int drain() {
        return drainer.drain(NvdSource.SOURCE_ID, batchSize,
                event -> advisories.upsert(parser.parse(event.payload())));
    }
}
