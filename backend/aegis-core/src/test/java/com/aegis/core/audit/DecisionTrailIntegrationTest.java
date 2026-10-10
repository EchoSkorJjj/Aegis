package com.aegis.core.audit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.aegis.core.actions.ticket.RiskTicket;
import com.aegis.core.actions.ticket.RiskTicketService;
import com.aegis.core.actions.ticket.RiskTicketStore;
import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.demo.DemoEstateSeeder;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.RepositoryUrl;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import com.aegis.core.support.FakeAiDrafter;
import com.aegis.core.support.FakeIssueTracker;
import com.aegis.core.support.FakeRepositoryHost;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The decision audit trail (E10 story 1): every step a response takes is recorded, in order, with who took it, why,
 * and the response as it stood then — the draft as drafted, the edit as edited, the override with its reason, the
 * approval and what came of it — for its ticket, its upgrade pull request and regulator memos alike. A refused step
 * leaves no record, a step whose record can't be written is undone, and an issue filed or pull request opened is
 * never recorded as failed. Only governance reads it (cross-organization isolation:
 * {@link DecisionTrailIsolationIntegrationTest}). Every test builds its own uniquely named service, component and
 * advisory, and reads only the records of its own response, change or service.
 */
class DecisionTrailIntegrationTest extends AbstractInventoryIntegrationTest {

    private static final String TRAIL = "/api/audit/decisions";
    private static final String TICKETS = "/api/actions/risk-tickets";
    private static final String PROPOSALS = "/api/actions/upgrade-proposals";
    private static final String MEMOS = "/api/actions/regulator-memos";

    @Autowired private InventoryService inventory;
    @Autowired private AdvisoryStore advisories;
    @Autowired private FakeIssueTracker tracker;
    @Autowired private FakeRepositoryHost host;
    @Autowired private DecisionTrail trail;
    @Autowired private RiskTicketService tickets;
    @Autowired private RiskTicketStore ticketStore;

    private final UUID drafter = UUID.randomUUID();
    private final UUID reviewer = UUID.randomUUID();
    private final UUID auditor = UUID.randomUUID();

    @AfterEach
    void reconnect() {
        tracker.connected(true);
    }

    @Test
    void everyStepOfAResponseIsRecordedWithTheTicketAsItStoodThen() throws Exception {
        Fixture f = fixture();
        String id = draftTicket(f);
        LocalDate due = LocalDate.now(ZoneOffset.UTC).plusDays(45);

        mvc.perform(post(TICKETS + "/{id}/edit", id).with(as("ANALYST", reviewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"summary\":\"Reviewed summary.\",\"risk\":\"Reviewed risk.\","
                                + "\"impact\":\"Reviewed impact.\",\"actions\":[\"Upgrade this sprint.\"]}"))
                .andExpect(status().isOk());
        // Refused (an outdated version): no record.
        mvc.perform(post(TICKETS + "/{id}/edit", id).with(as("ANALYST", reviewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"summary\":\"Stale.\",\"risk\":\"Stale.\",\"impact\":\"Stale.\","
                                + "\"actions\":[\"Stale.\"]}"))
                .andExpect(status().isConflict());
        mvc.perform(post(TICKETS + "/{id}/override", id).with(as("ANALYST", reviewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"priority\":\"LOW\",\"dueDate\":\"%s\",\"reason\":\"Behind the WAF\"}"
                                .formatted(due)))
                .andExpect(status().isOk());
        mvc.perform(post(TICKETS + "/{id}/approve", id).with(as("ANALYST", reviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILED"));

        String trail = mvc.perform(get(TRAIL).param("serviceId", f.service().getId().toString())
                        .param("changeSignalId", f.advisory().id().toString())
                        .with(as("COMPLIANCE_ADMIN", auditor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].action").value(
                        contains("DRAFTED", "EDITED", "OVERRIDDEN", "APPROVED", "FILED")))
                .andExpect(jsonPath("$.content[*].actorId").value(contains(drafter.toString(), reviewer.toString(),
                        reviewer.toString(), reviewer.toString(), reviewer.toString())))
                .andExpect(jsonPath("$.content[*].subject").value(everyItem(is("RISK_TICKET"))))
                .andExpect(jsonPath("$.content[0].responseId").value(id))
                .andExpect(jsonPath("$.content[0].subjectId").value(id))
                .andExpect(jsonPath("$.content[0].serviceName").value("Trail " + f.tag()))
                .andExpect(jsonPath("$.content[0].serviceIds").value(contains(f.service().getId().toString())))
                .andExpect(jsonPath("$.content[0].changeTitle").value(containsString(f.advisory().canonicalId())))
                .andExpect(jsonPath("$.content[0].tier").value("HIGH"))
                .andExpect(jsonPath("$.content[0].score").isNumber())
                .andExpect(jsonPath("$.content[0].occurredAt").isNotEmpty())
                // A listing leaves the snapshots out; each is read with its record.
                .andExpect(jsonPath("$.content[0].state").doesNotExist())
                .andExpect(jsonPath("$.content[1].reason").value(nullValue()))
                .andExpect(jsonPath("$.content[2].reason").value("Behind the WAF"))
                .andReturn().getResponse().getContentAsString();

        // The draft as drafted, with the evidence it was drafted on: the matching path and score arithmetic.
        detail(trail, 0)
                .andExpect(jsonPath("$.state.summary").value(FakeAiDrafter.TICKET.summary()))
                .andExpect(jsonPath("$.state.status").value("PROPOSED"))
                .andExpect(jsonPath("$.state.evidence").value(hasItem(startsWith("Score calculation:"))))
                .andExpect(jsonPath("$.state.evidence").value(hasItem(startsWith("Source excerpt:"))));
        // The edit as made, the draft kept beside it.
        detail(trail, 1)
                .andExpect(jsonPath("$.state.summary").value("Reviewed summary."))
                .andExpect(jsonPath("$.state.edit.draftedSummary").value(FakeAiDrafter.TICKET.summary()));
        // The override with why, and Aegis's own assessment: the priority it was drafted with.
        Object drafted = state(trail, 0);
        detail(trail, 2)
                .andExpect(jsonPath("$.state.priority").value("LOW"))
                .andExpect(jsonPath("$.state.dueDate").value(due.toString()))
                .andExpect(jsonPath("$.state.override.assessedPriority")
                        .value(JsonPath.<String>read(drafted, "$.priority")));
        // The approval, then what it filed.
        detail(trail, 3)
                .andExpect(jsonPath("$.state.status").value("FILING"))
                .andExpect(jsonPath("$.state.approval.by").value(reviewer.toString()));
        detail(trail, 4)
                .andExpect(jsonPath("$.state.status").value("FILED"))
                .andExpect(jsonPath("$.state.issueKey").value(startsWith("AEG-")));
    }

    @Test
    void withdrawingAnOverrideIsRecordedUnderWhoeverWithdrewIt() throws Exception {
        Fixture f = fixture();
        String id = draftTicket(f);
        UUID withdrawer = UUID.randomUUID();
        mvc.perform(post(TICKETS + "/{id}/override", id).with(as("ANALYST", reviewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"priority\":\"LOW\",\"dueDate\":null,\"reason\":\"Behind the WAF\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(TICKETS + "/{id}/override/withdraw", id).with(as("ANALYST", withdrawer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk());

        String trail = responseTrail(id)
                .andExpect(jsonPath("$.content[*].action")
                        .value(contains("DRAFTED", "OVERRIDDEN", "OVERRIDE_WITHDRAWN")))
                .andExpect(jsonPath("$.content[2].actorId").value(withdrawer.toString()))
                .andExpect(jsonPath("$.content[2].reason").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        detail(trail, 2)
                .andExpect(jsonPath("$.state.override").value(nullValue()))
                .andExpect(jsonPath("$.state.priority").value(JsonPath.<String>read(state(trail, 0), "$.priority")));
    }

    @Test
    void aFailedFilingAndTheDismissalAfterItAreRecordedWithWhy() throws Exception {
        Fixture f = fixture();
        String id = draftTicket(f);
        tracker.connected(false);
        mvc.perform(post(TICKETS + "/{id}/approve", id).with(as("ANALYST", reviewer)))
                .andExpect(status().isConflict());
        UUID dismisser = UUID.randomUUID();
        mvc.perform(post(TICKETS + "/{id}/dismiss", id).with(as("ANALYST", dismisser))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Filed by hand as PAY-17\"}"))
                .andExpect(status().isOk());

        String trail = responseTrail(id)
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[*].action").value(
                        contains("DRAFTED", "APPROVED", "FILING_FAILED", "DISMISSED")))
                .andExpect(jsonPath("$.content[2].actorId").value(reviewer.toString()))
                .andExpect(jsonPath("$.content[2].reason").value(FakeIssueTracker.NOT_CONNECTED))
                .andExpect(jsonPath("$.content[3].actorId").value(dismisser.toString()))
                .andExpect(jsonPath("$.content[3].reason").value("Filed by hand as PAY-17"))
                .andReturn().getResponse().getContentAsString();
        detail(trail, 2).andExpect(jsonPath("$.state.status").value("FAILED"));
        detail(trail, 3)
                .andExpect(jsonPath("$.state.status").value("DISMISSED"))
                .andExpect(jsonPath("$.state.approval.by").value(reviewer.toString()));
    }

    @Test
    void anIssueFiledThatAegisCannotRecordStaysFilingAndIsNeverRecordedAsFailed() throws Exception {
        Fixture f = fixture();
        String id = draftTicket(f);
        int filedBefore = tracker.filed().size();

        String error = withoutRecordsOf(id, "FILED", () -> mvc.perform(post(TICKETS + "/{id}/approve", id)
                        .with(as("ANALYST", reviewer)))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString());

        // Filed once; the approver is told where; the ticket waits as filing, not failed, so nobody files it again.
        assertThat(tracker.filed()).hasSize(filedBefore + 1);
        assertThat(JsonPath.<String>read(error, "$.message")).startsWith("Filed as AEG-")
                .contains("the response stays filing");
        mvc.perform(get(TICKETS + "/{id}", id).with(as("ANALYST", reviewer)))
                .andExpect(jsonPath("$.status").value("FILING"));
        responseTrail(id).andExpect(jsonPath("$.content[*].action").value(contains("DRAFTED", "APPROVED")));
    }

    @Test
    void anUpgradePullRequestsStepsAreRecordedUnderItsResponseWithItsScore() throws Exception {
        RepositoryFixture f = repositoryFixture();
        String created = draft(f.base());
        String id = JsonPath.read(created, "$.id");
        String pullRequest = JsonPath.read(created, "$.pullRequests[0].id");
        mvc.perform(post(TICKETS + "/{id}/approve", id).with(as("ANALYST", reviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILED"));
        assertThat(host.opened(f.repository())).hasSize(1);

        String trail = responseTrail(id)
                .andExpect(jsonPath("$.content[*].action").value(
                        contains("DRAFTED", "DRAFTED", "APPROVED", "FILED", "APPROVED", "OPENED")))
                .andExpect(jsonPath("$.content[*].subject").value(contains("RISK_TICKET", "UPGRADE_PULL_REQUEST",
                        "RISK_TICKET", "RISK_TICKET", "UPGRADE_PULL_REQUEST", "UPGRADE_PULL_REQUEST")))
                .andExpect(jsonPath("$.content[*].responseId").value(everyItem(is(id))))
                .andExpect(jsonPath("$.content[1].subjectId").value(pullRequest))
                .andExpect(jsonPath("$.content[1].actorId").value(drafter.toString()))
                .andExpect(jsonPath("$.content[4].actorId").value(reviewer.toString()))
                .andExpect(jsonPath("$.content[1].serviceIds").value(contains(f.base().service().getId().toString())))
                .andReturn().getResponse().getContentAsString();
        // The pull request's records carry its score and tier as drafted: its ticket's.
        assertThat(JsonPath.<Object>read(trail, "$.content[1].score")).isNotNull()
                .isEqualTo(JsonPath.read(trail, "$.content[0].score"));
        assertThat(JsonPath.<List<String>>read(trail, "$.content[*].tier")).containsOnly(
                JsonPath.<String>read(trail, "$.content[0].tier"));
        detail(trail, 5)
                .andExpect(jsonPath("$.state.status").value("OPENED"))
                .andExpect(jsonPath("$.state.pullRequestUrl").value(notNullValue()))
                .andExpect(jsonPath("$.state.title").value(startsWith("AEG-")));
    }

    @Test
    void aPullRequestThatFailsToOpenAndIsLeftOutIsRecordedWithWhy() throws Exception {
        RepositoryFixture f = repositoryFixture();
        String created = draft(f.base());
        String id = JsonPath.read(created, "$.id");
        String pullRequest = JsonPath.read(created, "$.pullRequests[0].id");
        host.failNextOpen(new ApiExceptions.UpstreamUnavailable("The repository host is unavailable"));
        mvc.perform(post(TICKETS + "/{id}/approve", id).with(as("ANALYST", reviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILED"));
        mvc.perform(post(PROPOSALS + "/{id}/dismiss", pullRequest).with(as("ANALYST", drafter))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"We upgrade by hand\"}"))
                .andExpect(status().isOk());

        responseTrail(id)
                .andExpect(jsonPath("$.content[*].action").value(
                        contains("DRAFTED", "DRAFTED", "APPROVED", "FILED", "APPROVED", "OPENING_FAILED", "DISMISSED")))
                .andExpect(jsonPath("$.content[5].subjectId").value(pullRequest))
                .andExpect(jsonPath("$.content[5].actorId").value(reviewer.toString()))
                .andExpect(jsonPath("$.content[5].reason").value("The repository host is unavailable"))
                .andExpect(jsonPath("$.content[6].subjectId").value(pullRequest))
                .andExpect(jsonPath("$.content[6].actorId").value(drafter.toString()))
                .andExpect(jsonPath("$.content[6].reason").value("We upgrade by hand"));
    }

    @Test
    void aPullRequestOpenedThatAegisCannotRecordStaysOpeningAndIsNeverRecordedAsFailed() throws Exception {
        RepositoryFixture f = repositoryFixture();
        String created = draft(f.base());
        String id = JsonPath.read(created, "$.id");
        String pullRequest = JsonPath.read(created, "$.pullRequests[0].id");

        withoutRecordsOf(pullRequest, "OPENED", () -> mvc.perform(post(TICKETS + "/{id}/approve", id)
                        .with(as("ANALYST", reviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILED")));

        assertThat(host.opened(f.repository())).hasSize(1);
        mvc.perform(get(PROPOSALS + "/{id}", pullRequest).with(as("ANALYST", reviewer)))
                .andExpect(jsonPath("$.status").value("OPENING"));
        responseTrail(id).andExpect(jsonPath("$.content[*].action").value(
                contains("DRAFTED", "DRAFTED", "APPROVED", "FILED", "APPROVED")));
    }

    @Test
    void aRegulatorMemoIsInTheTrailOfEveryServiceItCovers() throws Exception {
        Fixture f = fixture();
        BusinessService second = inventory.createBusinessService("Trail second " + f.tag(), BusinessCriticality.HIGH,
                ServiceExposure.INTERNET_FACING);
        inventory.addServiceComponent(second.getId(), f.component().getId());
        String created = mvc.perform(post(MEMOS).with(as("ANALYST", drafter)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeSignalId\":\"%s\"}".formatted(f.advisory().id())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String memo = JsonPath.read(created, "$.id");
        mvc.perform(post(MEMOS + "/{id}/approve", memo).with(as("ANALYST", reviewer)))
                .andExpect(status().isOk());

        String trail = mvc.perform(get(TRAIL).param("changeSignalId", f.advisory().id().toString())
                        .with(as("COMPLIANCE_ADMIN", auditor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].action").value(contains("DRAFTED", "APPROVED")))
                .andExpect(jsonPath("$.content[*].subject").value(contains("REGULATOR_MEMO", "REGULATOR_MEMO")))
                .andExpect(jsonPath("$.content[1].actorId").value(reviewer.toString()))
                .andExpect(jsonPath("$.content[1].responseId").value(memo))
                .andExpect(jsonPath("$.content[1].serviceId").value(nullValue()))
                .andExpect(jsonPath("$.content[1].serviceIds").value(containsInAnyOrder(
                        f.service().getId().toString(), second.getId().toString())))
                .andExpect(jsonPath("$.content[1].tier").value("HIGH"))
                .andReturn().getResponse().getContentAsString();
        detail(trail, 1)
                .andExpect(jsonPath("$.state.status").value("APPROVED"))
                .andExpect(jsonPath("$.state.body").value(containsString(f.advisory().canonicalId())));

        // Each service the memo covers has it in its trail, and in the item's chain.
        for (BusinessService service : List.of(f.service(), second)) {
            mvc.perform(get(TRAIL).param("serviceId", service.getId().toString())
                            .with(as("COMPLIANCE_ADMIN", auditor)))
                    .andExpect(jsonPath("$.content[?(@.responseId == '%s')].action".formatted(memo))
                            .value(contains("DRAFTED", "APPROVED")));
            mvc.perform(get(TRAIL).param("serviceId", service.getId().toString())
                            .param("changeSignalId", f.advisory().id().toString())
                            .with(as("COMPLIANCE_ADMIN", auditor)))
                    .andExpect(jsonPath("$.content[*].subject").value(contains("REGULATOR_MEMO", "REGULATOR_MEMO")));
        }
    }

    @Test
    void aDismissedRegulatorMemoIsRecordedWithWhy() throws Exception {
        Fixture f = fixture();
        String created = mvc.perform(post(MEMOS).with(as("ANALYST", drafter)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeSignalId\":\"%s\"}".formatted(f.advisory().id())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String memo = JsonPath.read(created, "$.id");
        mvc.perform(post(MEMOS + "/{id}/dismiss", memo).with(as("ANALYST", reviewer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Not a significant threat\"}"))
                .andExpect(status().isOk());

        String trail = responseTrail(memo)
                .andExpect(jsonPath("$.content[*].action").value(contains("DRAFTED", "DISMISSED")))
                .andExpect(jsonPath("$.content[1].actorId").value(reviewer.toString()))
                .andExpect(jsonPath("$.content[1].reason").value("Not a significant threat"))
                .andReturn().getResponse().getContentAsString();
        detail(trail, 1).andExpect(jsonPath("$.state.status").value("DISMISSED"));
    }

    @Test
    void aStepWhoseRecordCannotBeWrittenIsUndoneWithIt() throws Exception {
        Fixture f = fixture();
        RiskTicket before = tickets.get(UUID.fromString(draftTicket(f)));

        // The edit is saved and its own record written, then recording the step fails: all of it is rolled back.
        assertThatThrownBy(() -> trail.recorded(
                () -> tickets.edit(before.id(), reviewer, before.version(), "Never saved.", "Never saved.",
                        "Never saved.", List.of("Never saved.")),
                saved -> {
                    throw new IllegalStateException("The record could not be written");
                }))
                .hasMessage("The record could not be written");

        RiskTicket after = tickets.get(before.id());
        assertThat(after.summary()).isEqualTo(before.summary());
        assertThat(after.version()).isEqualTo(before.version());
        responseTrail(before.id().toString()).andExpect(jsonPath("$.content[*].action").value(contains("DRAFTED")));
    }

    @Test
    void aResponseIsNeverSavedOutsideTheTrail() throws Exception {
        Fixture f = fixture();
        RiskTicket ticket = tickets.get(UUID.fromString(draftTicket(f)));

        assertThatThrownBy(() -> ticketStore.update(ticket))
                .isInstanceOf(DecisionTrail.UnrecordedStep.class)
                .hasMessageContaining("decision trail");
        responseTrail(ticket.id().toString()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void onlyGovernanceReadsTheTrail() throws Exception {
        Fixture f = fixture();
        String id = draftTicket(f);
        String trail = responseTrail(id).andExpect(jsonPath("$.totalElements").value(1))
                .andReturn().getResponse().getContentAsString();
        String record = JsonPath.read(trail, "$.content[0].id");

        for (String path : List.of(TRAIL + "?responseId=" + id, TRAIL + "/" + record)) {
            mvc.perform(get(path).with(as("SERVICE_OWNER", reviewer))).andExpect(status().isForbidden());
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get(TRAIL + "/{id}", record).with(as("ANALYST", reviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.id").value(id));
        mvc.perform(get(TRAIL + "/{id}", UUID.randomUUID()).with(as("COMPLIANCE_ADMIN", auditor)))
                .andExpect(status().isNotFound());
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** The response drafted for the fixture's change in its service, as returned. */
    private String draft(Fixture f) throws Exception {
        return mvc.perform(post(TICKETS).with(as("ANALYST", drafter)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeSignalId\":\"%s\",\"serviceId\":\"%s\"}"
                                .formatted(f.advisory().id(), f.service().getId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private String draftTicket(Fixture f) throws Exception {
        return JsonPath.read(draft(f), "$.id");
    }

    private ResultActions responseTrail(String responseId) throws Exception {
        return mvc.perform(get(TRAIL).param("responseId", responseId).with(as("COMPLIANCE_ADMIN", auditor)))
                .andExpect(status().isOk());
    }

    /** The {@code index}th record of a listing, read on its own: with its snapshot. */
    private ResultActions detail(String listing, int index) throws Exception {
        return mvc.perform(get(TRAIL + "/{id}", JsonPath.<String>read(listing, "$.content[%d].id".formatted(index)))
                        .with(as("COMPLIANCE_ADMIN", auditor)))
                .andExpect(status().isOk());
    }

    /** The {@code index}th record's snapshot. */
    private Object state(String listing, int index) throws Exception {
        return JsonPath.read(detail(listing, index).andReturn().getResponse().getContentAsString(), "$.state");
    }

    /** A step of a test that may throw. */
    @FunctionalInterface
    private interface Step<T> {
        T run() throws Exception;
    }

    /**
     * Run {@code step} while the database refuses one kind of record for one subject, as if writing that record
     * failed; the refusal is lifted afterwards, whatever happens.
     */
    private <T> T withoutRecordsOf(String subjectId, String action, Step<T> step) throws Exception {
        String constraint = "ck_test_no_" + action.toLowerCase(Locale.ROOT) + "_" + subjectId.replace("-", "");
        JdbcTemplate superuser = new JdbcTemplate(superuserDataSource());
        superuser.execute("alter table core.decision_records add constraint " + constraint
                + " check (not (subject_id = '" + UUID.fromString(subjectId) + "' and action = '" + action + "'))"
                + " not valid");
        try {
            return step.run();
        } finally {
            superuser.execute("alter table core.decision_records drop constraint " + constraint);
        }
    }

    /** A high-criticality, internet-facing service directly using one Maven component a critical CVE affects. */
    private Fixture fixture() {
        String tag = Long.toHexString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        String artifact = "trail-lib-" + tag;
        BusinessService service = inventory.createBusinessService("Trail " + tag, BusinessCriticality.HIGH,
                ServiceExposure.INTERNET_FACING);
        SoftwareComponent component = inventory.createSoftwareComponent("maven", "com.acme:" + artifact, "1.0.0");
        inventory.addServiceComponent(service.getId(), component.getId());
        Advisory advisory = advisories.upsert(new Advisory(null, "osv", "GHSA-" + tag, "CVE-2096-" + tag,
                "A flaw in trail-lib.", Instant.now(), Instant.now(),
                new Advisory.Cvss("3.1", null, new BigDecimal("9.8"), "CRITICAL"),
                List.of(AffectedProduct.ofPackage("Maven", "com.acme:" + artifact, null, "1.0.0", null, null,
                        "1.1.0"))));
        return new Fixture(tag, service, component, advisory);
    }

    /** As {@link #fixture()}, with the service's repository linked and its manifest declaring the component. */
    private RepositoryFixture repositoryFixture() {
        Fixture f = fixture();
        String repository = "acme/trail-" + f.tag();
        inventory.linkServiceRepository(f.service().getId(),
                RepositoryUrl.parse("https://gitlab.com/" + repository, List.of("gitlab.com")), null);
        host.put(repository, "pom.xml", """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.acme</groupId>
                      <artifactId>trail-lib-%s</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(f.tag()));
        return new RepositoryFixture(f, repository);
    }

    private record Fixture(String tag, BusinessService service, SoftwareComponent component, Advisory advisory) {
    }

    private record RepositoryFixture(Fixture base, String repository) {
    }

    private static RequestPostProcessor as(String role, UUID userId) {
        return jwt().jwt(token -> token.subject(userId.toString())
                        .claim("org", DemoEstateSeeder.DEMO_ORG_ID.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
