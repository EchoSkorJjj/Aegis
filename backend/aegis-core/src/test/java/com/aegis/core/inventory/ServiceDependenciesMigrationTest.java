package com.aegis.core.inventory;

import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.aegis.core.support.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V29 against data as it was before: a scratch schema is migrated to V28, given services, links and organization-wide
 * edges, then migrated to V29. Every edge goes to each service that reached it — so no service's graph changes until
 * its next import — marked as the repository's for a service imported from its repository; an edge no service
 * reaches stays organization-wide.
 */
class ServiceDependenciesMigrationTest extends AbstractIntegrationTest {

    @Autowired private DataSource dataSource;

    @Test
    void existingEdgesGoToEveryServiceThatReachesThemAndNoServicesGraphChanges() {
        String schema = "v29_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            migrate(schema, "28");
            UUID org = UUID.randomUUID();
            UUID app = component(jdbc, schema, org, "app");
            UUID http = component(jdbc, schema, org, "http");
            UUID redirects = component(jdbc, schema, org, "redirects");
            UUID parent = component(jdbc, schema, org, "parent");
            UUID child = component(jdbc, schema, org, "child");
            UUID fromRepository = service(jdbc, schema, org, "Imported from its repository");
            UUID uploaded = service(jdbc, schema, org, "Uploaded");
            UUID bare = service(jdbc, schema, org, "Nothing linked");
            jdbc.update("insert into " + schema + ".business_service_components"
                    + " (business_service_id, component_id, org_id, manifest_paths, from_repository)"
                    + " values (?, ?, ?, array['package.json'], true)", fromRepository, app, org);
            jdbc.update("insert into " + schema + ".business_service_components (business_service_id, component_id, org_id)"
                    + " values (?, ?, ?)", uploaded, http, org);
            edge(jdbc, schema, org, app, http);
            edge(jdbc, schema, org, http, redirects);
            edge(jdbc, schema, org, parent, child); // nothing links parent

            migrate(schema, "29");

            assertThat(jdbc.queryForList("select business_service_id, component_id, dependency_id, from_repository from "
                    + schema + ".business_service_component_dependencies"))
                    .extracting(row -> List.of(row.get("business_service_id"), row.get("component_id"),
                            row.get("dependency_id"), row.get("from_repository")))
                    .containsExactlyInAnyOrder(
                            List.of(fromRepository, app, http, true),
                            List.of(fromRepository, http, redirects, true),
                            List.of(uploaded, http, redirects, false));
            assertThat(jdbc.queryForList("select component_id, dependency_id from " + schema
                    + ".software_component_dependencies"))
                    .extracting(row -> List.of(row.get("component_id"), row.get("dependency_id")))
                    .containsExactly(List.of(parent, child));
            assertThat(jdbc.queryForObject("select count(*) from " + schema
                    + ".business_service_component_dependencies where business_service_id = ?", Integer.class, bare))
                    .isZero();
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private void migrate(String schema, String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .target(target)
                .load()
                .migrate();
    }

    private static UUID component(JdbcTemplate jdbc, String schema, UUID org, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into " + schema + ".software_components (id, ecosystem, name, version, org_id)"
                + " values (?, 'npm', ?, '1.0.0', ?)", id, name, org);
        return id;
    }

    private static UUID service(JdbcTemplate jdbc, String schema, UUID org, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into " + schema + ".business_services (id, name, criticality, org_id)"
                + " values (?, ?, 'HIGH', ?)", id, name, org);
        return id;
    }

    private static void edge(JdbcTemplate jdbc, String schema, UUID org, UUID component, UUID dependency) {
        jdbc.update("insert into " + schema + ".software_component_dependencies (component_id, dependency_id, org_id)"
                + " values (?, ?, ?)", component, dependency, org);
    }
}
