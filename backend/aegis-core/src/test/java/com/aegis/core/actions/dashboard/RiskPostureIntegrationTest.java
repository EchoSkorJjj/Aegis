package com.aegis.core.actions.dashboard;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.aegis.core.actions.dashboard.PostureSnapshotStore.RecordedSnapshot;
import com.aegis.core.actions.dashboard.RiskPosture.Breakdown;
import com.aegis.core.actions.dashboard.RiskPosture.Snapshot;
import com.aegis.core.actions.triage.TriageQueue;
import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.platform.identity.IdentityInternalApi;
import com.aegis.core.platform.scheduling.SchedulerLease;
import com.aegis.core.platform.tenant.CurrentOrg;
import com.aegis.core.support.AbstractIntegrationTest;
import com.aegis.core.support.TestOwners;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RiskPostureIntegrationTest extends AbstractIntegrationTest {
    private static final String PATH = "/api/findings/posture";
    @Autowired private CurrentOrg currentOrg;
    @Autowired private InventoryService inventory;
    @Autowired private AdvisoryStore advisories;
    @Autowired private TestOwners owners;
    @Autowired private PostureSnapshotStore snapshots;
    @Autowired private DataSource application;
    @Autowired private RiskPosture posture;
    @Autowired private TriageQueue queue;

    private UUID org;
    private BusinessService service;
    private Owner owner;
    private Advisory advisory;

    @BeforeEach
    void estate() {
        org = UUID.randomUUID();
        String tag = UUID.randomUUID().toString();
        currentOrg.runAs(org, () -> {
            service = inventory.createBusinessService("Payments " + tag, BusinessCriticality.CRITICAL,
                    ServiceExposure.INTERNET_FACING);
            owner = owners.assign(service.getId(), "Owner " + tag);
            BusinessService unassigned = inventory.createBusinessService("Worker " + tag, BusinessCriticality.CRITICAL,
                    ServiceExposure.INTERNET_FACING);
            SoftwareComponent component = inventory.createSoftwareComponent("npm", "posture-" + tag, "1.0.0");
            inventory.addServiceComponent(service.getId(), component.getId());
            inventory.addServiceComponent(unassigned.getId(), component.getId());
        });
        advisory = advisories.upsert(new Advisory(null, "nvd", "CVE-posture-" + tag, "Posture " + tag,
                Instant.now(), Instant.now(), new Advisory.Cvss("3.1", null, new BigDecimal("10.0"), "CRITICAL"),
                List.of(AffectedProduct.ofPackage("npm", "posture-" + tag, null, "0", null, null, "2.0.0"))));
    }

    @Test
    void readsTheWholeQueueAndCountsByServiceAndOwnerThenDismissalClosesAnItem() throws Exception {
        mvc.perform(get(PATH).with(as("ANALYST", org)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.openItems").value(2))
                .andExpect(jsonPath("$.totals.newItems").value(2))
                .andExpect(jsonPath("$.totals.criticalItems").value(2))
                .andExpect(jsonPath("$.totals.unassignedItems").value(1))
                .andExpect(jsonPath("$.totals.affectedServices").value(2))
                .andExpect(jsonPath("$.services[*].openItems").value(containsInAnyOrder(1, 1)))
                .andExpect(jsonPath("$.owners[*].openItems").value(containsInAnyOrder(1, 1)))
                .andExpect(jsonPath("$.trend.length()").value(1))
                .andExpect(jsonPath("$.trend[0].openItems").value(2));
        String draft = mvc.perform(post("/api/actions/risk-tickets").with(as("ANALYST", org))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"serviceId\":\"%s\",\"changeSignalId\":\"%s\"}"
                                .formatted(service.getId(), advisory.id())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(get(PATH).with(as("ANALYST", org)))
                .andExpect(jsonPath("$.totals.inReviewItems").value(1));
        mvc.perform(post("/api/actions/risk-tickets/{id}/dismiss", (String) JsonPath.read(draft, "$.id"))
                        .with(as("ANALYST", org)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Accepted risk\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(PATH).with(as("ANALYST", org)))
                .andExpect(jsonPath("$.totals.openItems").value(1))
                .andExpect(jsonPath("$.trend.length()").value(1))
                .andExpect(jsonPath("$.trend[0].openItems").value(1));
        mvc.perform(get("/api/findings").param("open", "true").param("unassigned", "true")
                        .param("size", "1").with(as("ANALYST", org)))
                .andExpect(jsonPath("$.items.totalElements").value(1))
                .andExpect(jsonPath("$.items.content[0].owners.length()").value(0));
        mvc.perform(get("/api/findings").param("ownerId", owner.getId().toString())
                        .param("open", "true").with(as("ANALYST", org)))
                .andExpect(jsonPath("$.items.totalElements").value(0));
    }

    @Test
    void storesOnlyTheLatestDailyObservationAndKeepsGapsAndOtherOrganizationsSeparate() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Snapshot prior = new Snapshot(today.minusDays(2), today.minusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant(), 5, 2, 1);
        currentOrg.runAs(org, () -> {
            save(prior);
            save(new Snapshot(prior.day(), prior.capturedAt().minusSeconds(10), 9, 3, 2));
            save(new Snapshot(today.minusDays(30), Instant.now().minusSeconds(2592000), 12, 4, 3));
            assertThat(snapshots.between(today.minusDays(29), today)).containsExactly(prior);
        });
        UUID other = UUID.randomUUID();
        currentOrg.runAs(other, () -> save(new Snapshot(prior.day(), Instant.now(), 99, 50, 20)));
        mvc.perform(get(PATH).param("days", "30").with(as("ANALYST", org)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trend.length()").value(2))
                .andExpect(jsonPath("$.trend[0].openItems").value(5))
                .andExpect(jsonPath("$.trend[1].openItems").value(2));
        mvc.perform(get(PATH).with(as("ANALYST", other)))
                .andExpect(jsonPath("$.totals.totalServices").value(0))
                .andExpect(jsonPath("$.totals.openItems").value(0))
                .andExpect(jsonPath("$.trend[0].openItems").value(99));
    }

    @Test
    void theDatabaseAlsoEnforcesSnapshotIsolation() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        currentOrg.runAs(org, () -> save(new Snapshot(today, Instant.now(), 3, 1, 1)));
        UUID other = UUID.randomUUID();
        try (Connection connection = application.getConnection()) {
            connection.setAutoCommit(false);
            try (var bind = connection.prepareStatement("select set_config('aegis.current_org', ?, true)")) {
                bind.setString(1, other.toString());
                bind.execute();
            }
            try (var read = connection.prepareStatement("select count(*) from core.risk_posture_snapshots where org_id = ?")) {
                read.setObject(1, org);
                try (var rows = read.executeQuery()) {
                    rows.next();
                    assertThat(rows.getInt(1)).isZero();
                }
            }
            try (var read = connection.prepareStatement("select count(*) from core.risk_posture_breakdowns where org_id = ?")) {
                read.setObject(1, org);
                try (var rows = read.executeQuery()) {
                    rows.next();
                    assertThat(rows.getInt(1)).isZero();
                }
            }
            assertThatThrownBy(() -> {
                try (var insert = connection.prepareStatement("insert into core.risk_posture_breakdowns "
                        + "(org_id, day, dimension, subject_name, open_items, critical_items, high_items) "
                        + "values (?, ?, 'UNASSIGNED', 'Unassigned', 0, 0, 0)")) {
                    insert.setObject(1, org);
                    insert.setObject(2, today);
                    insert.executeUpdate();
                }
            }).hasMessageContaining("row-level security");
            connection.rollback();
            // A recycled connection with no organization bound sees none of the detailed history either.
            try (var read = connection.prepareStatement("select count(*) from core.risk_posture_breakdowns")) {
                try (var rows = read.executeQuery()) {
                    rows.next();
                    assertThat(rows.getInt(1)).isZero();
                }
            }
            assertThatThrownBy(() -> {
                try (var insert = connection.prepareStatement("insert into core.risk_posture_snapshots "
                        + "(org_id, day, captured_at, open_items, critical_items, high_items) values (?, ?, now(), 0, 0, 0)")) {
                    insert.setObject(1, org);
                    insert.setObject(2, today.plusDays(1));
                    insert.executeUpdate();
                }
            }).hasMessageContaining("row-level security");
            connection.rollback();
        }
    }

    @Test
    void theDailyJobRecordsServiceAndSharedOwnerCountsWithoutAnyoneOpeningTheDashboard() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Owner[] second = new Owner[1];
        currentOrg.runAs(org, () -> {
            second[0] = owners.assign(service.getId(), "Second owner");
            inventory.createBusinessService("Quiet", BusinessCriticality.LOW, ServiceExposure.INTERNAL);
        });
        UUID emptyOrg = UUID.randomUUID();
        IdentityInternalApi identity = mock(IdentityInternalApi.class);
        SchedulerLease lease = mock(SchedulerLease.class);
        when(identity.organizationIds()).thenReturn(List.of(org, emptyOrg));
        when(lease.held()).thenReturn(true);
        new PostureSnapshotJob(posture, identity, currentOrg, lease).run();

        currentOrg.runAs(org, () -> {
            RecordedSnapshot recorded = snapshots.recordedBetween(today, today).getFirst();
            assertThat(recorded.total().openItems()).isEqualTo(2);
            assertThat(recorded.services()).hasSize(3);
            assertThat(recorded.services()).filteredOn(row -> row.id().equals(service.getId()))
                    .extracting(Breakdown::openItems).containsExactly(1);
            assertThat(recorded.services()).filteredOn(row -> row.name().equals("Quiet"))
                    .extracting(Breakdown::openItems).containsExactly(0);
            assertThat(recorded.owners()).filteredOn(row -> owner.getId().equals(row.id()))
                    .extracting(Breakdown::openItems).containsExactly(1);
            assertThat(recorded.owners()).filteredOn(row -> second[0].getId().equals(row.id()))
                    .extracting(Breakdown::openItems).containsExactly(1);
            assertThat(recorded.owners()).filteredOn(row -> row.id() == null)
                    .extracting(Breakdown::openItems).containsExactly(1);
            assertThat(recorded.owners().stream().mapToInt(Breakdown::openItems).sum()).isEqualTo(3);
        });
        currentOrg.runAs(emptyOrg, () -> {
            RecordedSnapshot recorded = snapshots.recordedBetween(today, today).getFirst();
            assertThat(recorded.total().openItems()).isZero();
            assertThat(recorded.services()).isEmpty();
            assertThat(recorded.owners()).containsExactly(new Breakdown(null, "Unassigned", 0, 0, 0, null));
        });
    }

    @Test
    void pastServiceAndOwnerCountsSurviveReassignmentAndDeletion() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        Instant captured = yesterday.atTime(23, 55).toInstant(ZoneOffset.UTC);
        currentOrg.runAs(org, () -> {
            var previous = RiskPosture.aggregate(queue.overview(), captured);
            snapshots.save(new RecordedSnapshot(new Snapshot(yesterday, captured, previous.totals().openItems(),
                    previous.totals().criticalItems(), previous.totals().highItems()), previous.services(), previous.owners()));
            inventory.removeServiceOwner(service.getId(), owner.getId());
            owners.assign(service.getId(), "Successor");
            inventory.deleteBusinessService(service.getId());
            posture.capture();
            RecordedSnapshot past = snapshots.recordedBetween(yesterday, yesterday).getFirst();
            assertThat(past.services()).containsAll(previous.services());
            assertThat(past.owners()).containsAll(previous.owners());
            assertThat(past.owners()).filteredOn(row -> owner.getId().equals(row.id()))
                    .extracting(Breakdown::name).containsExactly(owner.getName());
        });
    }

    @Test
    void replacingADayRemovesStaleGroupsAndAnOlderCaptureCannotReplaceItsBreakdowns() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant at = today.atTime(12, 0).toInstant(ZoneOffset.UTC);
        currentOrg.runAs(org, () -> {
            save(new Snapshot(today, at, 3, 1, 1));
            RecordedSnapshot latest = new RecordedSnapshot(new Snapshot(today, at.plusSeconds(1), 0, 0, 0),
                    List.of(), List.of(new Breakdown(null, "Unassigned", 0, 0, 0, null)));
            snapshots.save(latest);
            save(new Snapshot(today, at.minusSeconds(1), 10, 4, 3));
            assertThat(snapshots.recordedBetween(today, today)).containsExactly(latest);
            assertThat(snapshots.between(today, today)).containsExactly(latest.total());
        });
    }

    @Test
    void aFailedBreakdownWriteRollsBackTheWholeObservation() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant at = today.atTime(12, 0).toInstant(ZoneOffset.UTC);
        currentOrg.runAs(org, () -> {
            save(new Snapshot(today, at, 3, 1, 1));
            RecordedSnapshot original = snapshots.recordedBetween(today, today).getFirst();
            RecordedSnapshot invalid = new RecordedSnapshot(new Snapshot(today, at.plusSeconds(1), 5, 2, 1),
                    original.services(), List.of(new Breakdown(null, "Unassigned", -1, 0, 0, 0.0)));
            assertThatThrownBy(() -> snapshots.save(invalid)).isInstanceOf(RuntimeException.class);
            assertThat(snapshots.recordedBetween(today, today)).containsExactly(original);
            assertThat(snapshots.between(today, today)).containsExactly(original.total());
        });
    }

    @Test
    void existingTotalsStayAvailableButTheirMissingBreakdownsAreNotInvented() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate previous = today.minusDays(1);
        new JdbcTemplate(superuserDataSource()).update("insert into core.risk_posture_snapshots "
                + "(org_id, day, captured_at, open_items, critical_items, high_items) values (?, ?, ?, 4, 2, 1)",
                org, previous, previous.atTime(23, 55).atOffset(ZoneOffset.UTC));
        currentOrg.runAs(org, () -> {
            assertThat(snapshots.between(previous, today)).hasSize(1);
            assertThat(snapshots.recordedBetween(previous, today)).isEmpty();
            posture.capture();
            assertThat(snapshots.between(previous, today)).hasSize(2);
            assertThat(snapshots.recordedBetween(previous, today)).hasSize(1);
            assertThat(snapshots.recordedBetween(previous, today).getFirst().total().day()).isEqualTo(today);
        });
    }

    @Test
    void protectsTheEndpointAndValidatesThePeriod() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        for (String role : List.of("USER", "ORG_ADMIN", "PLATFORM_ADMIN")) {
            mvc.perform(get(PATH).with(as(role, org))).andExpect(status().isForbidden());
        }
        for (String role : List.of("ANALYST", "COMPLIANCE_ADMIN", "SERVICE_OWNER")) {
            mvc.perform(get(PATH).param("days", "7").with(as(role, org))).andExpect(status().isOk());
        }
        mvc.perform(get(PATH).param("days", "90").with(as("ANALYST", org))).andExpect(status().isOk());
        mvc.perform(get(PATH).param("days", "8").with(as("ANALYST", org))).andExpect(status().isBadRequest());
    }

    @Test
    void theDefaultApiIncludesThirtyDaysOfGroupHistoryButNoOtherTenantsOrInventedLegacyGroups() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate first = today.minusDays(29);
        currentOrg.runAs(org, () -> {
            save(new Snapshot(first, first.atTime(23, 55).toInstant(ZoneOffset.UTC), 7, 3, 2));
            save(new Snapshot(first.minusDays(1), first.minusDays(1).atTime(23, 55).toInstant(ZoneOffset.UTC), 20, 8, 4));
        });
        LocalDate legacyDay = today.minusDays(1);
        new JdbcTemplate(superuserDataSource()).update("insert into core.risk_posture_snapshots "
                + "(org_id, day, captured_at, open_items, critical_items, high_items) values (?, ?, ?, 4, 2, 1)",
                org, legacyDay, legacyDay.atTime(23, 55).atOffset(ZoneOffset.UTC));
        UUID other = UUID.randomUUID();
        currentOrg.runAs(other, () -> save(new Snapshot(first, first.atTime(23, 55).toInstant(ZoneOffset.UTC), 99, 40, 20)));

        mvc.perform(get(PATH).with(as("ANALYST", org)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(first.toString()))
                .andExpect(jsonPath("$.to").value(today.toString()))
                .andExpect(jsonPath("$.trend.length()").value(3))
                .andExpect(jsonPath("$.history.length()").value(2))
                .andExpect(jsonPath("$.history[0].total.day").value(first.toString()))
                .andExpect(jsonPath("$.history[0].services[0].id").value(service.getId().toString()))
                .andExpect(jsonPath("$.history[0].services[0].openItems").value(7))
                .andExpect(jsonPath("$.history[0].owners[0].id").value(owner.getId().toString()))
                .andExpect(jsonPath("$.history[0].owners[0].openItems").value(7))
                .andExpect(jsonPath("$.history[1].total.day").value(today.toString()));
        mvc.perform(get(PATH).with(as("ANALYST", other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.openItems").value(0))
                .andExpect(jsonPath("$.history[0].services[0].openItems").value(99));
    }

    private static RequestPostProcessor as(String role, UUID org) {
        return jwt().jwt(builder -> builder.subject("00000000-0000-0000-0000-000000000123").claim("org", org.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private void save(Snapshot total) {
        snapshots.save(new RecordedSnapshot(total,
                List.of(new Breakdown(service.getId(), service.getName(), total.openItems(), total.criticalItems(),
                        total.highItems(), total.openItems() == 0 ? null : 100.0)),
                List.of(new Breakdown(owner.getId(), owner.getName(), total.openItems(), total.criticalItems(),
                        total.highItems(), total.openItems() == 0 ? null : 100.0))));
    }
}
