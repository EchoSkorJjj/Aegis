package com.aegis.core.inventory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.aegis.core.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The database's own backstop, beneath the stores' org filters: the application's role (not a superuser, as on
 * RDS) sees and writes only the rows of the organization bound for its transaction, and nothing when none is
 * bound — the same on a fresh connection and on one an earlier transaction bound an organization on (V31).
 */
class RowLevelSecurityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource application;

    private final JdbcTemplate superuser = new JdbcTemplate(superuserDataSource());

    @Test
    void theApplicationSeesOnlyTheBoundOrganizationsRowsAndNoneWithoutOne() throws SQLException {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        String prefix = "rls-" + UUID.randomUUID();
        service(prefix + "-a", orgA);
        service(prefix + "-b", orgB);

        try (Connection connection = application.getConnection()) {
            connection.setAutoCommit(false);
            bind(connection, orgA);
            assertThat(servicesNamed(connection, prefix)).containsExactly(prefix + "-a");
            connection.commit();

            // The same connection, a later transaction, no organization bound.
            assertThat(servicesNamed(connection, prefix)).isEmpty();
            connection.commit();
        }
    }

    @Test
    void theApplicationCannotWriteIntoAnotherOrganization() throws SQLException {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();

        try (Connection connection = application.getConnection()) {
            connection.setAutoCommit(false);
            bind(connection, orgA);
            assertThatThrownBy(() -> insertService(connection, "rls-" + UUID.randomUUID(), orgB))
                    .isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
            connection.rollback();
        }
    }

    private void service(String name, UUID org) {
        superuser.update("insert into core.business_services (id, name, criticality, org_id) values (?, ?, 'LOW', ?)",
                UUID.randomUUID(), name, org);
    }

    private static void bind(Connection connection, UUID org) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select set_config('aegis.current_org', ?, true)")) {
            statement.setString(1, org.toString());
            statement.execute();
        }
    }

    private static List<String> servicesNamed(Connection connection, String prefix) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select name from core.business_services where name like ? order by name")) {
            statement.setString(1, prefix + "%");
            List<String> names = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString(1));
                }
            }
            return names;
        }
    }

    private static void insertService(Connection connection, String name, UUID org) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into core.business_services (id, name, criticality, org_id) values (?, ?, 'LOW', ?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, name);
            statement.setObject(3, org);
            statement.executeUpdate();
        }
    }
}
