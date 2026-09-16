package com.aegis.core.ingestion;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionFeedMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private IngestionFeedMetrics metrics(Duration staleAfter, String... sourceIds) {
        List<IngestionSource> sources = List.of(sourceIds).stream()
                .<IngestionSource>map(FakeSource::new)
                .toList();
        IngestionProperties properties = new IngestionProperties(null, 5, new IngestionProperties.Health(staleAfter));
        return new IngestionFeedMetrics(registry, sources, properties);
    }

    @Test
    void everyConfiguredSourceIsRegisteredEagerlyAsUnhealthyWithNoData() {
        metrics(Duration.ofMinutes(45), "nvd", "vendor-status");

        for (String sourceId : List.of("nvd", "vendor-status")) {
            assertThat(health(sourceId)).isZero();
            assertThat(lastSuccess(sourceId)).isNaN();
        }
    }

    @Test
    void aSuccessfulPollIsHealthyAndRecordsCounts() {
        IngestionFeedMetrics metrics = metrics(Duration.ofMinutes(45), "nvd");

        metrics.recordPollSuccess("nvd", 3, 1);

        assertThat(health("nvd")).isEqualTo(1);
        assertThat(lastSuccess("nvd")).isCloseTo(Instant.now().getEpochSecond(), org.assertj.core.data.Offset.offset(5.0));
        assertThat(pollOutcome("nvd", "success")).isEqualTo(1);
        assertThat(pollRecords("nvd", "stored")).isEqualTo(3);
        assertThat(pollRecords("nvd", "duplicate")).isEqualTo(1);
    }

    @Test
    void aFeedThatHasNeverSucceededIsUnhealthyEvenWithoutAFailedAttemptYet() {
        IngestionFeedMetrics metrics = metrics(Duration.ofMinutes(45), "nvd");

        assertThat(health("nvd")).isZero();

        metrics.recordPollFailure("nvd");

        assertThat(health("nvd")).isZero();
        assertThat(pollOutcome("nvd", "failure")).isEqualTo(1);
    }

    @Test
    void aFeedThatFailsAfterASuccessBecomesUnhealthyEvenThoughItHasSucceededBefore() {
        IngestionFeedMetrics metrics = metrics(Duration.ofMinutes(45), "nvd");

        metrics.recordPollSuccess("nvd", 1, 0);
        assertThat(health("nvd")).isEqualTo(1);

        metrics.recordPollFailure("nvd");

        assertThat(health("nvd")).isZero();
        // The earlier success timestamp is untouched by a later failure.
        assertThat(lastSuccess("nvd")).isNotNaN();
    }

    @Test
    void aFeedIsUnhealthyOnceItsLastSuccessIsOlderThanTheStaleAfterThreshold() throws InterruptedException {
        // A real (if tiny) sleep guarantees some elapsed time has actually passed, so this doesn't
        // race the clock's resolution the way asserting against a zero elapsed time would.
        IngestionFeedMetrics metrics = metrics(Duration.ofMillis(1), "nvd");

        metrics.recordPollSuccess("nvd", 1, 0);
        Thread.sleep(20);

        assertThat(health("nvd")).isZero();
    }

    @Test
    void normalizeOutcomesAreRecordedPerFeedEvenBeforeItHasEverPolled() {
        IngestionFeedMetrics metrics = metrics(Duration.ofMinutes(45), "nvd");

        metrics.recordNormalizeOutcome("nvd", 5, 1, 2);

        assertThat(normalizeRecords("nvd", "normalized")).isEqualTo(5);
        assertThat(normalizeRecords("nvd", "dropped")).isEqualTo(1);
        assertThat(normalizeRecords("nvd", "deferred")).isEqualTo(2);
        // Normalizing doesn't itself make a feed healthy — that's poll success's job.
        assertThat(health("nvd")).isZero();
    }

    private double health(String sourceId) {
        return registry.get("aegis.ingestion.health").tag("source_id", sourceId).gauge().value();
    }

    private double lastSuccess(String sourceId) {
        return registry.get("aegis.ingestion.last.success.timestamp.seconds")
                .tag("source_id", sourceId).gauge().value();
    }

    private double pollOutcome(String sourceId, String outcome) {
        return registry.get("aegis.ingestion.poll")
                .tag("source_id", sourceId).tag("outcome", outcome).counter().count();
    }

    private double pollRecords(String sourceId, String result) {
        return registry.get("aegis.ingestion.poll.records")
                .tag("source_id", sourceId).tag("result", result).counter().count();
    }

    private double normalizeRecords(String sourceId, String result) {
        return registry.get("aegis.ingestion.normalize.records")
                .tag("source_id", sourceId).tag("result", result).counter().count();
    }

    private record FakeSource(String sourceId) implements IngestionSource {
        @Override
        public List<RawEvent> poll() {
            return List.of();
        }
    }
}
