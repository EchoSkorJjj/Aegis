package com.aegis.core.advisories;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aegis.core.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

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

    @Test
    void anAdvisoryIngestedBeforeV42GetsItsDetailsAndFixCommitsFromItsNewestRawPayload() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String osv = "GHSA-backfill-" + tag;
        String nvd = "CVE-2097-" + tag;
        JdbcTemplate superuser = new JdbcTemplate(superuserDataSource());
        // As V41 left them: the summary only.
        insertAdvisory(superuser, "osv", osv, "Summary of " + tag);
        insertAdvisory(superuser, "nvd", nvd, "The full NVD description.");
        insertRaw(superuser, "osv", osv + "@older", "2026-01-01T00:00:00Z", """
                {"id": "%s", "summary": "Summary", "details": "An older revision.", "references": []}
                """.formatted(osv));
        insertRaw(superuser, "osv", osv + "@newer", "2026-02-01T00:00:00Z", """
                {"id": "%s", "summary": "Summary", "details": "  The newest details.  ",
                 "references": [{"type": "WEB", "url": "https://github.com/o/r/commit/%s"},
                                {"type": "WEB", "url": "https://github.com/o/r/pull/12/commits/%s"},
                                {"type": "FIX", "url": "https://gitlab.com/g/sub/p/-/commit/ABCDEF1?x=1"},
                                {"type": "WEB", "url": "https://evil.example/o/r/commit/%s"},
                                {"type": "WEB", "url": "https://github.com/o/r/pull/12"}]}
                """.formatted(osv, SHA, SHA, SHA));
        insertRaw(superuser, "osv", osv + "@broken", "2026-03-01T00:00:00Z", "{not json");
        insertRaw(superuser, "nvd", nvd + "@2026-01-01T00:00:00.000", "2026-01-01T00:00:00Z", """
                {"id": "%s", "descriptions": [], "references": [{"url": "https://github.com/n/v/commit/%s",
                 "tags": ["Patch"]}]}
                """.formatted(nvd, SHA));

        runV42(superuser);

        Advisory backfilled = advisories.findByExternalId("osv", osv).orElseThrow();
        assertThat(backfilled.details()).isEqualTo("The newest details.");
        assertThat(backfilled.fixCommits()).containsExactly("https://github.com/o/r/commit/" + SHA,
                "https://gitlab.com/g/sub/p/-/commit/abcdef1");
        Advisory fromNvd = advisories.findByExternalId("nvd", nvd).orElseThrow();
        assertThat(fromNvd.details()).isNull(); // its description already is the full text
        assertThat(fromNvd.fixCommits()).containsExactly("https://github.com/n/v/commit/" + SHA);
    }

    private static Advisory advisory(String id, String details, List<String> fixCommits) {
        return new Advisory(null, "osv", id, id, "Summary", Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"), null, List.of(), details, fixCommits);
    }

    private static void insertAdvisory(JdbcTemplate jdbc, String source, String id, String description) {
        jdbc.update("insert into core.advisories (id, source_id, external_id, canonical_id, description)"
                + " values (?, ?, ?, ?, ?)", UUID.randomUUID(), source, id, id, description);
    }

    private static void insertRaw(JdbcTemplate jdbc, String source, String externalId, String ingestedAt,
            String payload) {
        jdbc.update("insert into core.raw_events (id, source_id, external_id, dedup_key, content_type, payload,"
                + " ingested_at, processed_at) values (?, ?, ?, ?, 'application/json', ?, ?::timestamptz, now())",
                UUID.randomUUID(), source, externalId, source + ":" + externalId, payload, ingestedAt);
    }

    /** V42 again: it is written to run more than once, filling only what is still empty. */
    private static void runV42(JdbcTemplate jdbc) throws Exception {
        String sql = new ClassPathResource("db/migration/V42__advisory_details.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        try (Connection connection = jdbc.getDataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("set search_path to core");
            statement.execute(sql);
        }
    }
}
