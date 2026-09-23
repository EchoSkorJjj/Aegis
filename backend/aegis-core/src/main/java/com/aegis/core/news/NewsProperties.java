package com.aegis.core.news;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Security news connector configuration ({@code aegis.ingestion.news.*}). Disabled by default so
 * neither the test suite nor a plain local run reaches out to news publishers.
 *
 * <p>Only credential-free RSS and Atom feeds are supported on purpose: a "news API" that needs a key
 * would put a secret in configuration, and every worthwhile security publisher offers a feed.
 */
@ConfigurationProperties("aegis.ingestion.news")
public record NewsProperties(
        boolean enabled,
        List<Feed> feeds,
        Duration connectTimeout,
        Duration readTimeout,
        int maxPayloadBytes,
        int normalizeBatchSize,
        long maxResponseBytes,
        int maxRedirects,
        boolean allowPrivateHosts) {

    public NewsProperties {
        feeds = feeds == null ? List.of() : List.copyOf(feeds);
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            connectTimeout = Duration.ofSeconds(5);
        }
        // Without this a hung publisher would block the shared ingestion thread indefinitely.
        if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            readTimeout = Duration.ofSeconds(15);
        }
        // Untrusted third-party content: cap what one publisher's document may push into the raw store.
        if (maxPayloadBytes < 1) {
            maxPayloadBytes = 4 * 1024 * 1024;
        }
        if (normalizeBatchSize < 1) {
            normalizeBatchSize = 50;
        }
        // Cap the whole HTTP response so a publisher cannot stream an unbounded body into memory.
        if (maxResponseBytes < 1) {
            maxResponseBytes = 8L * 1024 * 1024;
        }
        if (maxRedirects < 0) {
            maxRedirects = 5;
        }
    }

    /**
     * One publisher's feed. {@code key} is the stable identifier used in the raw event's external id
     * and on the normalised item; {@code name} is what a reader sees as the source of an article.
     */
    public record Feed(String key, String name, String url) {

        public Feed {
            if (key == null || key.isBlank() || key.length() > 64) {
                throw new IllegalArgumentException("news feed key must be non-blank and at most 64 characters");
            }
            if (key.indexOf(':') >= 0) {
                // The external id is "<key>:<entry id>", so a colon here would break the split.
                throw new IllegalArgumentException("news feed key must not contain ':' (got '" + key + "')");
            }
            if (name == null || name.isBlank() || name.length() > 255) {
                throw new IllegalArgumentException("news feed name must be non-blank and at most 255 characters");
            }
            if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
                throw new IllegalArgumentException("news feed url must be an http(s) URL (got '" + url + "')");
            }
        }
    }
}
