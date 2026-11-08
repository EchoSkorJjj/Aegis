package com.aegis.core.advisories;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aegis.core.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An advisory's full text and fix commits (V42, E11-5): kept beside its summary for an assessment to read, and filled
 * in for advisories ingested before V42 from the newest raw payload the feed sent.
 */
class AdvisoryDetailsIntegrationTest extends AbstractIntegrationTest {

    private static final String SHA = "cc6756b61ed90b6b9227f670e0408d5d9bd48551";

    @Autowired
    private AdvisoryStore advisories;

    @Test
    void theDetailsAndFixCommitsAreKeptAndARevisionReplacesThem() {
        String id = "GHSA-details-" + UUID.randomUUID().toString().substring(0, 8);
        advisories.upsert(advisory(id, "Full text.", List.of("https://github.com/o/r/commit/" + SHA)));

        Advisory stored = advisories.findByExternalId("osv", id).orElseThrow();
        assertThat(stored.details()).isEqualTo("Full text.");
        assertThat(stored.fixCommits()).containsExactly("https://github.com/o/r/commit/" + SHA);

        advisories.upsert(advisory(id, null, List.of()));
        Advisory revised = advisories.findByExternalId("osv", id).orElseThrow();
        assertThat(revised.details()).isNull();
        assertThat(revised.fixCommits()).isEmpty();
    }

    private static Advisory advisory(String id, String details, List<String> fixCommits) {
        return new Advisory(null, "osv", id, id, "Summary", Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"), null, List.of(), details, fixCommits);
    }

}
