package com.aegis.core.ingestion.connectors.vendorstatus;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Vendor status/outage connector configuration ({@code aegis.ingestion.vendor-status.*}). Disabled by
 * default so neither the test suite nor a plain local run reaches out to third-party status pages.
 *
 * <p>The vendor list is YAML-shaped rather than a single env var, because each entry needs four
 * fields. Override it per deployment with the vendors the estate actually depends on.
 */
@ConfigurationProperties("aegis.ingestion.vendor-status")
public record VendorStatusProperties(
        boolean enabled,
        List<Vendor> vendors,
        Duration connectTimeout,
        Duration readTimeout,
        int maxPayloadBytes,
        int normalizeBatchSize,
        long maxResponseBytes,
        int maxRedirects,
        boolean allowPrivateHosts) {

    public VendorStatusProperties {
        vendors = vendors == null ? List.of() : List.copyOf(vendors);
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            connectTimeout = Duration.ofSeconds(5);
        }
        // Without this a hung status page would block the shared ingestion thread indefinitely.
        if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            readTimeout = Duration.ofSeconds(15);
        }
        // Feed content is untrusted: cap what one vendor's document may push into the raw store.
        if (maxPayloadBytes < 1) {
            maxPayloadBytes = 2 * 1024 * 1024;
        }
        if (normalizeBatchSize < 1) {
            normalizeBatchSize = 50;
        }
        // Cap the whole HTTP response so a status page cannot stream an unbounded body into memory.
        if (maxResponseBytes < 1) {
            maxResponseBytes = 8L * 1024 * 1024;
        }
        if (maxRedirects < 0) {
            maxRedirects = 5;
        }
    }

    /** How a vendor publishes its status; decides which reader parses the document. */
    public enum Format {
        /** Atlassian Statuspage JSON API, e.g. {@code https://<host>/api/v2/incidents.json}. */
        STATUSPAGE,
        /** An RSS 2.0 or Atom feed of status history. */
        RSS
    }

    /**
     * One vendor's status feed. {@code key} is the stable identifier used in the raw event's external
     * id and on the normalised incident; {@code name} is what matching resolves against the
     * inventory's registered vendors, so it should match how the vendor is named there.
     */
    public record Vendor(String key, String name, String url, Format format) {

        public Vendor {
            if (key == null || key.isBlank() || key.length() > 64) {
                throw new IllegalArgumentException("vendor key must be non-blank and at most 64 characters");
            }
            if (key.indexOf(':') >= 0) {
                // The external id is "<key>:<incident id>", so a colon here would break the split.
                throw new IllegalArgumentException("vendor key must not contain ':' (got '" + key + "')");
            }
            if (name == null || name.isBlank() || name.length() > 255) {
                throw new IllegalArgumentException("vendor name must be non-blank and at most 255 characters");
            }
            if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
                throw new IllegalArgumentException("vendor url must be an http(s) URL (got '" + url + "')");
            }
            if (format == null) {
                format = Format.STATUSPAGE;
            }
        }

        String contentType() {
            return format == Format.RSS ? "application/rss+xml" : "application/json";
        }
    }
}
