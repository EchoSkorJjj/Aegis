package com.aegis.core.ingestion;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionRetryTest {

    /** Zero delays keep the test instant while still exercising the attempt/backoff loop. */
    private static IngestionRetry retryWith(int maxAttempts) {
        return new IngestionRetry(new IngestionProperties(
                new IngestionProperties.Retry(maxAttempts, Duration.ZERO, 2.0, Duration.ZERO)));
    }

    @Test
    void retriesTransientFailuresThenSucceeds() throws Exception {
        IngestionRetry retry = retryWith(3);
        AtomicInteger calls = new AtomicInteger();

        String result = retry.call("test", () -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("transient");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
    }

    @Test
    void rethrowsAfterExhaustingAttempts() {
        IngestionRetry retry = retryWith(3);
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retry.call("test", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("always fails");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("always fails");
        assertThat(calls).hasValue(3);
    }

    @Test
    void singleAttemptDoesNotRetry() {
        IngestionRetry retry = retryWith(1);
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retry.call("test", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(1);
    }
}
