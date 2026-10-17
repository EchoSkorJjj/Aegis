package com.aegis.core.ingestion.connectors.vendorstatus;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.aegis.core.incidents.VendorIncident;
import com.aegis.core.incidents.VendorIncidentStore;
import com.aegis.core.ingestion.RawEventDrainer;
import com.aegis.core.ingestion.UnparseableEventException;
import com.aegis.core.ingestion.connectors.vendorstatus.VendorStatusProperties.Format;
import com.aegis.core.ingestion.connectors.vendorstatus.VendorStatusProperties.Vendor;
import com.aegis.core.platform.scheduling.SchedulerLease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains captured status documents into normalised incidents.
 *
 * <p>One raw event holds a whole vendor document, so unlike the NVD drain this writes many records
 * per event. Each is an upsert keyed on {@code (source, vendor-key:incident-id)}, which is what makes
 * re-reading an unchanged incident harmless: a vendor edits incidents in place as it investigates,
 * and the same row is updated rather than duplicated.
 */
@Component
@ConditionalOnProperty(name = "aegis.ingestion.vendor-status.enabled", havingValue = "true")
class VendorStatusNormalizer {

    private static final Logger log = LoggerFactory.getLogger(VendorStatusNormalizer.class);

    private final RawEventDrainer drainer;
    private final VendorIncidentStore incidents;
    private final StatuspageFeedReader statuspageReader;
    private final RssFeedReader rssReader;
    private final Map<String, Vendor> vendorsByKey;
    private final int batchSize;
    private final SchedulerLease lease;

    VendorStatusNormalizer(
            RawEventDrainer drainer,
            VendorIncidentStore incidents,
            StatuspageFeedReader statuspageReader,
            RssFeedReader rssReader,
            VendorStatusProperties properties,
            SchedulerLease lease) {
        this.drainer = drainer;
        this.incidents = incidents;
        this.statuspageReader = statuspageReader;
        this.rssReader = rssReader;
        this.batchSize = properties.normalizeBatchSize();
        this.vendorsByKey = properties.vendors().stream()
                .collect(Collectors.toMap(Vendor::key, Function.identity(), (first, second) -> first));
        this.lease = lease;
    }

    @Scheduled(
            fixedDelayString = "${aegis.ingestion.vendor-status.normalize-interval:PT1M}",
            initialDelayString = "${aegis.ingestion.vendor-status.normalize-interval:PT1M}")
    void normalizePending() {
        if (lease.held()) {
            drain();
        }
    }

    /**
     * Normalise the pending status documents. Returns how many incidents were written — not how many
     * documents were read, which is what the drainer counts. Package-visible so the drain can be
     * tested without waiting on the scheduler.
     */
    int drain() {
        int[] written = {0};
        drainer.drain(VendorStatusSource.SOURCE_ID, batchSize, event -> {
            List<VendorIncident> parsed;
            try {
                // Terminal (drop, keeping the raw row): a malformed document, or a vendor no longer
                // configured. Only the upsert below is transient and worth retrying.
                Vendor vendor = vendorFor(event.externalId());
                parsed = readerFor(vendor).read(event.payload(), vendor);
            } catch (RuntimeException terminal) {
                throw new UnparseableEventException(VendorStatusSource.SOURCE_ID, event.externalId(), terminal);
            }
            parsed.forEach(incidents::upsert);
            written[0] += parsed.size();
        });
        if (written[0] > 0) {
            log.info("vendor-status: {} incident(s) normalised", written[0]);
        }
        return written[0];
    }

    /**
     * @throws IllegalStateException the event names a vendor no longer configured. The drainer treats
     *     that as terminal and drops the event; its raw payload is retained, so re-adding the vendor
     *     and clearing {@code processed_at} replays it.
     */
    private Vendor vendorFor(String externalId) {
        String key = VendorStatusSource.vendorKeyOf(externalId);
        Vendor vendor = vendorsByKey.get(key);
        if (vendor == null) {
            throw new IllegalStateException("no configured vendor for key '" + key + "'");
        }
        return vendor;
    }

    private VendorFeedReader readerFor(Vendor vendor) {
        return vendor.format() == Format.RSS ? rssReader : statuspageReader;
    }
}
