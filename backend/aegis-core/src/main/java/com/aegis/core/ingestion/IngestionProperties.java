package com.aegis.core.ingestion;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ingestion framework configuration ({@code aegis.ingestion.*}).
 *
 * <p>{@code enabled} (default true) and {@code poll-interval} (default 15m) are consumed directly by
 * {@link IngestionScheduler}'s annotations; this type binds the {@code retry} policy used per source
 * poll. Every value has a sensible default, so connectors added by later stories need no extra config.
 */
@ConfigurationProperties("aegis.ingestion")
public record IngestionProperties(Retry retry) {

    public IngestionProperties {
        if (retry == null) {
            retry = new Retry(0, null, 0, null);
        }
    }

    /**
     * Bounded exponential backoff for a single source poll: attempt {@code n} waits
     * {@code initialDelay * multiplier^(n-1)}, capped at {@code maxDelay}. When every attempt fails the
     * poll gives up until the next scheduled cycle.
     */
    public record Retry(int maxAttempts, Duration initialDelay, double multiplier, Duration maxDelay) {

        public Retry {
            if (maxAttempts < 1) {
                maxAttempts = 3;
            }
            if (initialDelay == null) {
                initialDelay = Duration.ofSeconds(1);
            }
            if (multiplier < 1.0) {
                multiplier = 2.0;
            }
            if (maxDelay == null) {
                maxDelay = Duration.ofSeconds(30);
            }
        }
    }
}
