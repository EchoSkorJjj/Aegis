package com.aegis.core.ingestion.nvd;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.aegis.core.ingestion.RawEvent;
import com.aegis.core.ingestion.RawEventStore;
import com.aegis.core.ingestion.advisory.Advisory;
import com.aegis.core.ingestion.advisory.AdvisoryStore;
import com.aegis.core.ingestion.advisory.AffectedProduct;
import com.aegis.core.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The connector's second half end to end: a captured NVD payload becomes a normalised advisory, and
 * the raw event is marked processed so the next drain does not redo it.
 *
 * <p>The feed scheduler is switched off and the drain interval pushed out, so nothing runs in the
 * background while the test asserts — the drain is invoked directly.
 */
@TestPropertySource(properties = {
        "aegis.ingestion.nvd.enabled=true",
        "aegis.ingestion.enabled=false",
        "aegis.ingestion.nvd.normalize-interval=PT30M"
})
class NvdNormalizerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NvdNormalizer normalizer;

    @Autowired
    private RawEventStore rawEvents;

    @Autowired
    private AdvisoryStore advisories;

    private static String fixtureWithId(String cveId) throws IOException {
        return new ClassPathResource("nvd/cve-2021-44228.json")
                .getContentAsString(StandardCharsets.UTF_8)
                .replace("CVE-2021-44228", cveId);
    }

    private static String uniqueCveId() {
        return "CVE-2098-" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000);
    }

    @Test
    void turnsACapturedCveIntoANormalisedAdvisory() throws Exception {
        String cveId = uniqueCveId();
        rawEvents.saveIfNew(NvdSource.SOURCE_ID, new RawEvent(
                cveId + "@2023-11-07T03:39:04.677",
                "application/json",
                fixtureWithId(cveId),
                Instant.parse("2023-11-07T03:39:04.677Z")));

        assertThat(normalizer.drain()).isPositive();

        Advisory advisory = advisories.findByExternalId(NvdSource.SOURCE_ID, cveId).orElseThrow();
        assertThat(advisory.id()).isNotNull();
        assertThat(advisory.description()).startsWith("Apache Log4j2");
        assertThat(advisory.lastModifiedAt()).isEqualTo(Instant.parse("2023-11-07T03:39:04.677Z"));
        assertThat(advisory.cvss().version()).isEqualTo("3.1");
        assertThat(advisory.cvss().baseScore()).isEqualByComparingTo(new BigDecimal("10.0"));
        assertThat(advisory.cvss().severity()).isEqualTo("CRITICAL");
        assertThat(advisory.affected())
                .extracting(AffectedProduct::vendor, AffectedProduct::product)
                .contains(tuple("apache", "log4j"), tuple("escaped:vendor", "escaped:product"));

        // Drained, so a second pass has nothing left to do.
        assertThat(rawEvents.findUnprocessed(NvdSource.SOURCE_ID, 100)).isEmpty();
        assertThat(normalizer.drain()).isZero();
    }

    @Test
    void marksAnUnparseablePayloadProcessedRatherThanBlockingTheQueue() throws Exception {
        rawEvents.saveIfNew(NvdSource.SOURCE_ID, new RawEvent(
                "malformed-" + UUID.randomUUID(),
                "application/json",
                "{\"descriptions\":[]}", // no CVE id, so nothing can key it
                Instant.parse("2023-11-07T03:39:04.677Z")));

        normalizer.drain();

        assertThat(rawEvents.findUnprocessed(NvdSource.SOURCE_ID, 100)).isEmpty();
    }
}
