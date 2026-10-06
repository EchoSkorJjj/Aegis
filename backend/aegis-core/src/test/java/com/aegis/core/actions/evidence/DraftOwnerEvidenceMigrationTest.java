package com.aegis.core.actions.evidence;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.aegis.core.support.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests V32 against historical rows in an isolated schema, migrating as a non-superuser table owner. */
class DraftOwnerEvidenceMigrationTest extends AbstractIntegrationTest {

    private final DataSource superuser = superuserDataSource();

    @Test
    void undecidedEvidenceIsRedactedAcrossTenantsWhileDecidedRecordsKeepWhatWasDecided() throws Exception {
        String schema = "v32_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        JdbcTemplate jdbc = new JdbcTemplate(superuser);
        DataSource owner;
        try (Connection connection = superuser.getConnection()) {
            owner = new DriverManagerDataSource(connection.getMetaData().getURL(), "aegis", "aegis");
        }
        List<Sample> samples = List.of(
                new Sample("CVE-1 affects library 1.0; dependency path app -> library reaches service Payments; owners: Alice, Bob",
                        "CVE-1 affects library 1.0; dependency path app -> library reaches service Payments; owners: [REDACTED]"),
                new Sample("CVE-2 affects library 2.0; owners: Alice (Finance), 李明 (possible match — version is unknown, review before acting)",
                        "CVE-2 affects library 2.0; owners: [REDACTED] (possible match — version is unknown, review before acting)"),
                new Sample("Outage affects vendor Cloud; service Payments depends on that vendor; owners: Former Owner\r\n",
                        "Outage affects vendor Cloud; service Payments depends on that vendor; owners: [REDACTED]\r\n"),
                new Sample("CVE-3 affects library; owners: unassigned", "CVE-3 affects library; owners: unassigned"),
                new Sample("CVE-4 affects library; owners: unassigned (possible match — review before acting)",
                        "CVE-4 affects library; owners: unassigned (possible match — review before acting)"),
                new Sample("CVE-5 affects library; owners: [REDACTED]", "CVE-5 affects library; owners: [REDACTED]"),
                new Sample("No owner clause in this evidence.", "No owner clause in this evidence."),
                // Pull request bodies drafted before V32 joined every explanation on one line.
                new Sample("CVE-1 affects a 1.0; dependency path a reaches service S; owners: Alice Tan "
                        + "CVE-1 affects b 2.0; dependency path x -> b reaches service S; owners: Alice Tan",
                        "CVE-1 affects a 1.0; dependency path a reaches service S; owners: [REDACTED] "
                        + "CVE-1 affects b 2.0; dependency path x -> b reaches service S; owners: [REDACTED]"),
                new Sample("GHSA-2 affects a 1.0; owners: Alice (possible match — matched on an ambiguous package name, "
                        + "review before acting) GHSA-2 affects b 2.0; owners: unassigned",
                        "GHSA-2 affects a 1.0; owners: [REDACTED] (possible match — matched on an ambiguous package name, "
                        + "review before acting) GHSA-2 affects b 2.0; owners: unassigned"),
                new Sample("CVE-6 affects a 1.0; owners: unassigned CVE-6 affects b 2.0; owners: Carol",
                        "CVE-6 affects a 1.0; owners: unassigned CVE-6 affects b 2.0; owners: [REDACTED]"),
                new Sample("INC-1 affects vendor Cloud; service S depends on that vendor; owners: Dana "
                        + "INC-1 affects vendor Mail; service S depends on that vendor; owners: Dana, Eve",
                        "INC-1 affects vendor Cloud; service S depends on that vendor; owners: [REDACTED] "
                        + "INC-1 affects vendor Mail; service S depends on that vendor; owners: [REDACTED]"));
        try {
            migrate(owner, schema, "31");
            UUID[] organizations = {UUID.randomUUID(), UUID.randomUUID()};
            for (int index = 0; index < samples.size(); index++) {
                Sample sample = samples.get(index);
                UUID org = organizations[index % organizations.length];
                UUID service = UUID.randomUUID();
                jdbc.update("insert into " + schema + ".business_services (id, org_id, name, criticality)"
                        + " values (?, ?, ?, 'HIGH')", service, org, "Payments " + index);
                seed(jdbc, schema, org, service, sample.pending(), sample.raw(),
                        index % 2 == 0 ? Stage.PROPOSED : Stage.FAILED);
                seed(jdbc, schema, org, service, sample.decided(), sample.raw(),
                        index % 2 == 0 ? Stage.SENT : Stage.DISMISSED);
            }
            List<String> tables = List.of("risk_tickets", "upgrade_proposals", "regulator_memos");
            List<List<String>> metadata = tables.stream().map(table -> metadata(jdbc, schema, table)).toList();

            migrate(owner, schema, "32");

            for (Sample sample : samples) {
                assertEvidence(jdbc, schema, sample.pending(), sample.expected(),
                        sample.raw().equals(sample.expected()) ? 7L : 8L);
                // What a decision was made on stays as it was: the filed issue, opened PR or final memo.
                assertEvidence(jdbc, schema, sample.decided(), sample.raw(), 7L);
            }
            for (int index = 0; index < tables.size(); index++) {
                String table = tables.get(index);
                assertThat(metadata(jdbc, schema, table)).isEqualTo(metadata.get(index));
                assertThat(jdbc.queryForObject("select relrowsecurity and relforcerowsecurity from pg_class"
                        + " where oid = ?::regclass", Boolean.class, schema + "." + table)).isTrue();
                assertThat(new JdbcTemplate(owner).queryForObject("select count(*) from " + schema + "." + table,
                        Integer.class)).as("forced RLS still hides all rows without an organization").isZero();
            }

            // Replaying the cleanup is harmless: no second version bump and no changes to protected text.
            try (Connection connection = owner.getConnection()) {
                connection.setAutoCommit(false);
                connection.setSchema(schema);
                try (var statement = connection.createStatement()) {
                    statement.execute(new ClassPathResource("db/migration/V32__redact_historical_draft_owner_evidence.sql")
                            .getContentAsString(StandardCharsets.UTF_8));
                }
                connection.commit();
            }
            for (Sample sample : samples) {
                for (String table : tables) {
                    assertThat(version(jdbc, schema, table, sample.pending()))
                            .isEqualTo(sample.raw().equals(sample.expected()) ? 7L : 8L);
                    assertThat(version(jdbc, schema, table, sample.decided())).isEqualTo(7L);
                }
            }
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private static void assertEvidence(JdbcTemplate jdbc, String schema, UUID id, String evidence, long version) {
        assertThat(jdbc.queryForObject("select evidence[1] from " + schema + ".risk_tickets where id = ?",
                String.class, id)).isEqualTo(evidence);
        assertThat(jdbc.queryForObject("select evidence[2] from " + schema + ".risk_tickets where id = ?",
                String.class, id)).isEqualTo("Source: osv / GHSA-1.");
        for (String table : List.of("upgrade_proposals", "regulator_memos")) {
            assertThat(jdbc.queryForObject("select body from " + schema + "." + table + " where id = ?",
                    String.class, id)).isEqualTo(body(evidence));
        }
        for (String table : List.of("risk_tickets", "upgrade_proposals", "regulator_memos")) {
            assertThat(version(jdbc, schema, table, id)).isEqualTo(version);
        }
    }

    private static long version(JdbcTemplate jdbc, String schema, String table, UUID id) {
        return jdbc.queryForObject("select version from " + schema + "." + table + " where id = ?", Long.class, id);
    }

    private static void migrate(DataSource owner, String schema, String target) {
        Flyway.configure().dataSource(owner).schemas(schema).defaultSchema(schema).createSchemas(true)
                .target(target).load().migrate();
    }

    private static List<String> metadata(JdbcTemplate jdbc, String schema, String table) {
        return jdbc.queryForList("select (to_jsonb(row) - 'evidence' - 'body' - 'version' - 'updated_at')::text"
                + " from " + schema + "." + table + " as row order by id", String.class);
    }

    private static String body(String evidence) {
        return "# Draft\n- " + evidence + "\nSource: osv / GHSA-1.\nImpact score 64.8 (MEDIUM).\n"
                + "Score calculation: severity 0.9 x exposure 0.9 x criticality 0.8 x confidence 1 x 100 = 64.8\n";
    }

    private static void seed(JdbcTemplate jdbc, String schema, UUID org, UUID service, UUID id, String evidence,
            Stage stage) {
        UUID change = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        java.sql.Timestamp decidedAt = java.sql.Timestamp.valueOf("2026-09-26 08:30:00");
        jdbc.update("insert into " + schema + ".risk_tickets (id, org_id, service_id, change_signal_id, signal_type,"
                + " change_title, title, priority, score, tier, labels, summary, risk, impact, actions, evidence,"
                + " ai_drafted, citations, status, created_by, decided_by, decided_at, version)"
                + " values (?, ?, ?, ?, 'VULNERABILITY', 'CVE-1', 'Risk', 'HIGH', 64.8, 'MEDIUM', array['security'],"
                + " 'Summary', 'Risk', 'Impact', array['Upgrade'], array[?, 'Source: osv / GHSA-1.'], false,"
                + " array[]::text[], ?, ?, ?, ?, 7)", id, org, service, change, evidence, stage.ticket, author,
                stage.decided(stage.ticket) ? approver : null, stage.decided(stage.ticket) ? decidedAt : null);
        jdbc.update("insert into " + schema + ".upgrade_proposals (id, org_id, service_id, change_signal_id,"
                + " vulnerability_id, repository_url, head_branch, match_confidence, title, body, status, created_by,"
                + " decided_by, decided_at, edits, version)"
                + " values (?, ?, ?, ?, 'CVE-1', 'https://github.com/test/example', 'upgrade', 'CONFIRMED', 'Upgrade',"
                + " ?, ?, ?, ?, ?, '[]', 7)", id, org, service, change, body(evidence), stage.pullRequest, author,
                stage.decided(stage.pullRequest) ? approver : null, stage.decided(stage.pullRequest) ? decidedAt : null);
        jdbc.update("insert into " + schema + ".regulator_memos (id, org_id, change_signal_id, signal_type, change_title,"
                + " kind, reference, services, summary, impact, measures, next_steps, ai_drafted, citations, body,"
                + " status, created_by, decided_by, decided_at, version)"
                + " values (?, ?, ?, 'VULNERABILITY', 'CVE-1', 'CYBER_THREAT', 'AEGIS-1', '[]', 'Summary', 'Impact',"
                + " 'Measures', 'Next steps', false, array[]::text[], ?, ?, ?, ?, ?, 7)", id, org, change,
                body(evidence), stage.memo, author, stage.decided(stage.memo) ? approver : null,
                stage.decided(stage.memo) ? decidedAt : null);
    }

    /** Each table's status at one stage of a response; memos have no FAILED and stay undecided. */
    private enum Stage {
        PROPOSED("PROPOSED", "PROPOSED", "PROPOSED"),
        FAILED("FAILED", "FAILED", "PROPOSED"),
        SENT("FILED", "OPENED", "APPROVED"),
        DISMISSED("DISMISSED", "DISMISSED", "DISMISSED");

        private final String ticket;
        private final String pullRequest;
        private final String memo;

        Stage(String ticket, String pullRequest, String memo) {
            this.ticket = ticket;
            this.pullRequest = pullRequest;
            this.memo = memo;
        }

        boolean decided(String status) {
            return !status.equals("PROPOSED");
        }
    }

    private record Sample(UUID pending, UUID decided, String raw, String expected) {
        Sample(String raw, String expected) {
            this(UUID.randomUUID(), UUID.randomUUID(), raw, expected);
        }
    }
}
