package com.aegis.core.ingestion.nvd;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * NVD connector configuration ({@code aegis.ingestion.nvd.*}). Disabled by default so neither the
 * test suite nor a plain local run reaches out to nvd.nist.gov; Docker Compose turns it on.
 *
 * <p>{@code apiKey} is optional. Without one NVD allows 5 requests per rolling 30 seconds; with one,
 * 50. {@code pageDelay} keeps a multi-page fetch inside the unauthenticated limit.
 */
@ConfigurationProperties("aegis.ingestion.nvd")
public record NvdProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        Duration initialWindow,
        Duration overlap,
        int resultsPerPage,
        int maxPages,
        Duration pageDelay,
        Duration connectTimeout,
        Duration readTimeout,
        int maxPayloadBytes,
        int normalizeBatchSize,
        long maxResponseBytes,
        boolean allowPrivateHosts) {

    public NvdProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://services.nvd.nist.gov/rest/json/cves/2.0";
        }
        if (apiKey == null) {
            apiKey = "";
        }
        // How far back the very first poll reaches, before a watermark exists.
        if (initialWindow == null || initialWindow.isNegative() || initialWindow.isZero()) {
            initialWindow = Duration.ofDays(2);
        }
        // Re-poll a little before the watermark so an advisory revised mid-poll is never skipped;
        // de-duplication absorbs the repeats.
        if (overlap == null || overlap.isNegative()) {
            overlap = Duration.ofMinutes(10);
        }
        if (resultsPerPage < 1 || resultsPerPage > 2000) {
            resultsPerPage = 1000;
        }
        if (maxPages < 1) {
            maxPages = 10;
        }
        if (pageDelay == null || pageDelay.isNegative()) {
            pageDelay = Duration.ofSeconds(6);
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            connectTimeout = Duration.ofSeconds(5);
        }
        // Without this a hung feed would block the shared ingestion thread indefinitely.
        if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            readTimeout = Duration.ofSeconds(30);
        }
        // Feed content is untrusted: cap what one event may push into the raw store. Sized off real
        // NVD data — a CVE enumerating thousands of CPEs runs past 1 MB, and dropping those would
        // lose exactly the broad-impact advisories that matter most.
        if (maxPayloadBytes < 1) {
            maxPayloadBytes = 5 * 1024 * 1024;
        }
        if (normalizeBatchSize < 1) {
            normalizeBatchSize = 200;
        }
        // Cap the whole HTTP response (not just one CVE) so a compromised endpoint cannot stream an
        // unbounded body into memory; set well above a legitimate 1000-CVE page.
        if (maxResponseBytes < 1) {
            maxResponseBytes = 64L * 1024 * 1024;
        }
    }
}
