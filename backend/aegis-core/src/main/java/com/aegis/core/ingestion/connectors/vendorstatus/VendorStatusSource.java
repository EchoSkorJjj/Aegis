package com.aegis.core.ingestion.connectors.vendorstatus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.aegis.core.ingestion.IngestionSource;
import com.aegis.core.ingestion.RawEvent;
import com.aegis.core.ingestion.connectors.feed.Digests;
import com.aegis.core.ingestion.connectors.feed.FeedHttpClient;
import com.aegis.core.ingestion.connectors.vendorstatus.VendorStatusProperties.Vendor;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Ingestion connector for third-party status pages: Statuspage JSON for vendors that publish it, RSS
 * or Atom for those that don't. Each poll captures every configured vendor's status document exactly
 * as returned; turning documents into incidents is {@link VendorStatusNormalizer}'s job.
 *
 * <p><b>Whole-document capture.</b> Unlike the NVD feed there is no time-window or cursor API here —
 * a status page simply serves its current document. So the connector stores the document as one raw
 * event keyed by {@code <vendor-key>@<sha256 of the document>}: an unchanged feed hashes to an event
 * already stored and is de-duplicated away, while any edit is a new event. The raw store therefore
 * keeps one copy per distinct state of each vendor's page, and nothing is re-parsed needlessly.
 *
 * <p><b>Per-vendor isolation.</b> The framework already isolates one source's failure from another's,
 * but this source speaks to many vendors, so it also catches per vendor: one status page being down,
 * slow, or malformed must not cost the others their poll.
 */
@Component
@ConditionalOnProperty(name = "aegis.ingestion.vendor-status.enabled", havingValue = "true")
class VendorStatusSource implements IngestionSource {

    static final String SOURCE_ID = "vendor-status";

    private static final Logger log = LoggerFactory.getLogger(VendorStatusSource.class);

    private final FeedHttpClient client;
    private final VendorStatusProperties properties;

    VendorStatusSource(VendorStatusProperties properties, ObservationRegistry observationRegistry) {
        this.client = new FeedHttpClient(
                properties.connectTimeout(),
                properties.readTimeout(),
                properties.maxResponseBytes(),
                properties.maxRedirects(),
                properties.allowPrivateHosts(),
                observationRegistry);
        this.properties = properties;
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<RawEvent> poll() {
        if (properties.vendors().isEmpty()) {
            log.debug("vendor-status: no vendors configured");
            return List.of();
        }
        List<RawEvent> events = new ArrayList<>();
        for (Vendor vendor : properties.vendors()) {
            try {
                capture(vendor).ifPresent(events::add);
            } catch (Exception ex) {
                log.warn("vendor-status: '{}' feed failed ({}): {}", vendor.key(), vendor.url(), ex.toString());
            }
        }
        log.debug("vendor-status: captured {} of {} vendor feed(s)", events.size(), properties.vendors().size());
        return events;
    }

    private Optional<RawEvent> capture(Vendor vendor) {
        String body = client.fetch(vendor.url());
        if (body == null || body.isBlank()) {
            log.warn("vendor-status: '{}' returned an empty document", vendor.key());
            return Optional.empty();
        }
        int bytes = body.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > properties.maxPayloadBytes()) {
            log.warn("vendor-status: skipping '{}' — document {} bytes exceeds the {} byte cap",
                    vendor.key(), bytes, properties.maxPayloadBytes());
            return Optional.empty();
        }
        // Content-addressed: an unchanged page de-duplicates, any edit is a new event.
        String externalId = vendor.key() + "@" + Digests.sha256(body);
        return Optional.of(new RawEvent(externalId, vendor.contentType(), body, null));
    }

    /** The vendor key a raw event was captured for, taken back off its external id. */
    static String vendorKeyOf(String externalId) {
        if (externalId == null) {
            return null;
        }
        int at = externalId.indexOf('@');
        return at < 0 ? externalId : externalId.substring(0, at);
    }
}
