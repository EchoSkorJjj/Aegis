package com.aegis.core.ingestion;

import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs a single source poll with bounded exponential backoff. Transient failures are retried a few
 * times within the poll cycle; if every attempt fails the exception is rethrown and the scheduler
 * moves on (retrying the source on the next scheduled cycle).
 */
@Component
class IngestionRetry {

    private static final Logger log = LoggerFactory.getLogger(IngestionRetry.class);

    private final IngestionProperties.Retry config;

    IngestionRetry(IngestionProperties properties) {
        this.config = properties.retry();
    }

    /** Invoke {@code operation}, retrying on any exception up to {@code maxAttempts} with backoff. */
    <T> T call(String sourceId, Callable<T> operation) throws Exception {
        long delayMillis = config.initialDelay().toMillis();
        for (int attempt = 1; ; attempt++) {
            try {
                return operation.call();
            } catch (Exception ex) {
                if (attempt >= config.maxAttempts()) {
                    throw ex;
                }
                log.warn(
                        "ingestion source '{}' attempt {}/{} failed ({}); retrying in {}ms",
                        sourceId, attempt, config.maxAttempts(), ex.toString(), delayMillis);
                sleep(delayMillis);
                delayMillis = nextDelay(delayMillis);
            }
        }
    }

    private long nextDelay(long currentMillis) {
        long scaled = (long) (currentMillis * config.multiplier());
        return Math.min(Math.max(scaled, currentMillis), config.maxDelay().toMillis());
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ingestion retry interrupted", ex);
        }
    }
}
