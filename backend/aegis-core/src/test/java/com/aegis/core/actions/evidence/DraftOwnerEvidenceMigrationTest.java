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
    void historicalEvidenceIsRedactedAcrossTenantsWithoutChangingFactsOrDecisions() throws Exception {
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
                new Sample("No owner clause in this evidence.", "No owner clause in this evidence."));
        try {
            migrate(owner, schema, "31");
            UUID[] organizations = {UUID.randomUUID(), UUID.randomUUID()};
            for (int index = 0; index < samples.size(); index++) {
                Sample sample = samples.get(index);
                UUID org = organizations[index % organizations.length];
                UUID service = UUID.randomUUID();
                jdbc.update("insert into " + schema + ".business_services (id, org_id, name, criticality)"
                        + " values (?, ?, ?, 'HIGH')", service, org, "Payments " + index);
                seed(jdbc, schema, org, service, sample, index % 2 == 0);
            }
            List<String> tables = List.of("risk_tickets", "upgrade_proposals", "regulator_memos");
            List<List<String>> metadata = tables.stream().map(table -> metadata(jdbc, schema, table)).toList();

            migrate(owner, schema, "32");

            for (Sample sample : samples) {
                assertThat(jdbc.queryForObject("select evidence[1] from " + schema + ".risk_tickets where id = ?",
                        String.class, sample.id())).isEqualTo(sample.expected());
                assertThat(jdbc.queryForObject("select evidence[2] from " + schema + ".risk_tickets where id = ?",
                        String.class, sample.id())).isEqualTo("Source: osv / GHSA-1.");
                for (String table : List.of("upgrade_proposals", "regulator_memos")) {
                    assertThat(jdbc.queryForObject("select body from " + schema + "." + table + " where id = ?",
                            String.class, sample.id())).isEqualTo(body(sample.expected()));
                }
                for (String table : tables) {
                    assertThat(jdbc.queryForObject("select version from " + schema + "." + table + " where id = ?",
                            Long.class, sample.id())).isEqualTo(sample.raw().equals(sample.expected()) ? 7L : 8L);
                }
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
                    assertThat(jdbc.queryForObject("select version from " + schema + "." + table + " where id = ?",
                            Long.class, sample.id())).isEqualTo(sample.raw().equals(sample.expected()) ? 7L : 8L);
                }
            }
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
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

    private static void seed(JdbcTemplate jdbc, String schema, UUID org, UUID service, Sample sample, boolean decided) {
        UUID change = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        UUID approver = decided ? UUID.randomUUID() : null;
        jdbc.update("insert into " + schema + ".risk_tickets (id, org_id, service_id, change_signal_id, signal_type,"
                + " change_title, title, priority, score, tier, labels, summary, risk, impact, actions, evidence,"
                + " ai_drafted, citations, status, created_by, decided_by, decided_at, version)"
                + " values (?, ?, ?, ?, 'VULNERABILITY', 'CVE-1', 'Risk', 'HIGH', 64.8, 'MEDIUM', array['security'],"
                + " 'Summary', 'Risk', 'Impact', array['Upgrade'], array[?, 'Source: osv / GHSA-1.'], false,"
                + " array[]::text[], ?, ?, ?, ?, 7)", sample.id(), org, service, change, sample.raw(),
                decided ? "FILED" : "PROPOSED", author, approver,
                decided ? java.sql.Timestamp.valueOf("2026-09-26 08:30:00") : null);
        jdbc.update("insert into " + schema + ".upgrade_proposals (id, org_id, service_id, change_signal_id,"
                + " vulnerability_id, repository_url, head_branch, match_confidence, title, body, status, created_by,"
                + " decided_by, decided_at, edits, version)"
                + " values (?, ?, ?, ?, 'CVE-1', 'https://github.com/test/example', 'upgrade', 'CONFIRMED', 'Upgrade',"
                + " ?, ?, ?, ?, ?, '[]', 7)", sample.id(), org, service, change, body(sample.raw()),
                decided ? "OPENED" : "PROPOSED", author, approver,
                decided ? java.sql.Timestamp.valueOf("2026-09-26 08:30:00") : null);
        jdbc.update("insert into " + schema + ".regulator_memos (id, org_id, change_signal_id, signal_type, change_title,"
                + " kind, reference, services, summary, impact, measures, next_steps, ai_drafted, citations, body,"
                + " status, created_by, decided_by, decided_at, version)"
                + " values (?, ?, ?, 'VULNERABILITY', 'CVE-1', 'CYBER_THREAT', 'AEGIS-1', '[]', 'Summary', 'Impact',"
                + " 'Measures', 'Next steps', false, array[]::text[], ?, ?, ?, ?, ?, 7)", sample.id(), org, change,
                body(sample.raw()), decided ? "APPROVED" : "PROPOSED", author, approver,
                decided ? java.sql.Timestamp.valueOf("2026-09-26 08:30:00") : null);
    }

    private record Sample(UUID id, String raw, String expected) {
        Sample(String raw, String expected) {
            this(UUID.randomUUID(), raw, expected);
        }
    }
}
