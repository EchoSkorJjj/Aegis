package com.aegis.core.audit;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.demo.DemoEstateSeeder;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The decision audit trail is immutable (E10 story 1): the database refuses to change or remove a record — whoever
 * asks, the application's own role in its own organization or the superuser — and later changes to the estate, the
 * feed or the response itself (an SBOM that no longer has the component, a service re-graded, an advisory re-scored,
 * the service deleted) never alter what a past record says. Every test builds its own uniquely named service,
 * component and advisory, and touches only its own records.
 */
class DecisionRecordImmutabilityIntegrationTest extends AbstractInventoryIntegrationTest {

    private static final String TRAIL = "/api/audit/decisions";
    private static final String TICKETS = "/api/actions/risk-tickets";

    @Autowired private InventoryService inventory;
    @Autowired private AdvisoryStore advisories;
    @Autowired private DataSource dataSource;

    private final UUID drafter = UUID.randomUUID();
    private final UUID reviewer = UUID.randomUUID();

    @Test
    void theApplicationCannotChangeOrRemoveARecordInItsOwnOrganization() throws Exception {
        String response = approvedResponse(fixture());
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                bindOrg(connection);
                assertThat(count(connection, response)).isEqualTo(3); // visible to it: row-level security passes
                assertRefused(connection, "update core.decision_records set reason = 'rewritten' where response_id = ?",
                        response, "UPDATE");
                bindOrg(connection);
                assertRefused(connection, "update core.decision_records set actor_id = gen_random_uuid() where response_id = ?",
                        response, "UPDATE");
                bindOrg(connection);
                assertRefused(connection, "delete from core.decision_records where response_id = ?", response, "DELETE");
            } finally {
                connection.rollback();
            }
        }
        assertTrailIntact(response);
    }

    @Test
    void notEvenTheSuperuserCanChangeRemoveOrTruncateTheTrail() throws Exception {
        String response = approvedResponse(fixture());
        try (Connection connection = superuserDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                assertRefused(connection, "update core.decision_records set state = '{}' where response_id = ?", response,
                        "UPDATE");
                assertRefused(connection, "delete from core.decision_records where response_id = ?", response, "DELETE");
                try (Statement truncate = connection.createStatement()) {
                    assertThatThrownBy(() -> truncate.execute("truncate core.decision_records"))
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("decision_records is append-only; TRUNCATE is not permitted");
                }
            } finally {
                connection.rollback();
            }
        }
        assertTrailIntact(response);
    }

    @Test
    void laterChangesToTheEstateTheFeedOrTheServiceNeverAlterPastRecords() throws Exception {
        Fixture f = fixture();
        String response = approvedResponse(f);
        String before = trail(response);
        assertThat(JsonPath.<Double>read(before, "$.content[0].score")).isGreaterThan(0.0);
        assertThat(JsonPath.<String>read(before, "$.content[0].tier")).isEqualTo("HIGH");

        // The SBOM no longer has the component, the service is re-graded, the feed re-scores the advisory.
        inventory.removeServiceComponent(f.service().getId(), f.component().getId());
        inventory.updateBusinessService(f.service().getId(), "Renamed " + f.tag(), BusinessCriticality.LOW);
        advisories.upsert(advisory(f.tag(), f.component(), new BigDecimal("2.0"), "LOW"));
        assertThat(trail(response)).isEqualTo(before);

        // The service goes, and its tickets with it (V23); the trail stays.
        inventory.deleteBusinessService(f.service().getId());
        mvc.perform(get(TICKETS + "/{id}", response).with(as("ANALYST", reviewer)))
                .andExpect(status().isNotFound());
        assertThat(trail(response)).isEqualTo(before);
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** A drafted response approved and filed: three records (drafted, approved, filed). */
    private String approvedResponse(Fixture f) throws Exception {
        String created = mvc.perform(post(TICKETS).with(as("ANALYST", drafter)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeSignalId\":\"%s\",\"serviceId\":\"%s\"}"
                                .formatted(f.advisory().id(), f.service().getId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        mvc.perform(post(TICKETS + "/{id}/approve", id).with(as("ANALYST", reviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILED"));
        return id;
    }

    private String trail(String response) throws Exception {
        return mvc.perform(get(TRAIL).param("responseId", response).with(as("COMPLIANCE_ADMIN", reviewer)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void assertTrailIntact(String response) throws Exception {
        mvc.perform(get(TRAIL).param("responseId", response).with(as("COMPLIANCE_ADMIN", reviewer)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[2].action").value("FILED"))
                .andExpect(jsonPath("$.content[2].actorId").value(reviewer.toString()));
    }

    /**
     * The statement fails with the trigger's error. A failed statement aborts the transaction, so the caller rolls
     * back (and binds its organization again) before the next one.
     */
    private static void assertRefused(Connection connection, String sql, String response, String operation)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.fromString(response));
            assertThatThrownBy(statement::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("decision_records is append-only; " + operation + " is not permitted");
        }
        connection.rollback();
    }

    private static void bindOrg(Connection connection) throws SQLException {
        try (PreparedStatement bind = connection.prepareStatement("select set_config('aegis.current_org', ?, true)")) {
            bind.setString(1, DemoEstateSeeder.DEMO_ORG_ID.toString());
            bind.executeQuery().close();
        }
    }

    private static int count(Connection connection, String response) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "select count(*) from core.decision_records where response_id = ?")) {
            query.setObject(1, UUID.fromString(response));
            var rows = query.executeQuery();
            rows.next();
            return rows.getInt(1);
        }
    }

    /** A high-criticality, internet-facing service directly using one Maven component a critical CVE affects. */
    private Fixture fixture() {
        String tag = Long.toHexString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        BusinessService service = inventory.createBusinessService("Immutable " + tag, BusinessCriticality.HIGH,
                ServiceExposure.INTERNET_FACING);
        SoftwareComponent component = inventory.createSoftwareComponent("maven", "com.acme:sealed-lib-" + tag, "1.0.0");
        inventory.addServiceComponent(service.getId(), component.getId());
        Advisory advisory = advisories.upsert(advisory(tag, component, new BigDecimal("9.8"), "CRITICAL"));
        return new Fixture(tag, service, component, advisory);
    }

    private static Advisory advisory(String tag, SoftwareComponent component, BigDecimal cvss, String rating) {
        return new Advisory(null, "osv", "GHSA-" + tag, "CVE-2095-" + tag, "A flaw in sealed-lib.", Instant.now(),
                Instant.now(), new Advisory.Cvss("3.1", null, cvss, rating),
                List.of(AffectedProduct.ofPackage("Maven", component.getName(), null, "1.0.0", null, null, "1.1.0")));
    }

    private record Fixture(String tag, BusinessService service, SoftwareComponent component, Advisory advisory) {
    }

    private static RequestPostProcessor as(String role, UUID userId) {
        return jwt().jwt(token -> token.subject(userId.toString())
                        .claim("org", DemoEstateSeeder.DEMO_ORG_ID.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
