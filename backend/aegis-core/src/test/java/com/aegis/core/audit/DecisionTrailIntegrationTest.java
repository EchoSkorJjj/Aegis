package com.aegis.core.audit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.demo.DemoEstateSeeder;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.platform.tenant.CurrentOrg;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import com.aegis.core.support.FakeAiDrafter;
import com.aegis.core.support.FakeIssueTracker;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
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
 * approval and what came of it. A refused step leaves no record. Only governance reads it, and only its own
 * organization's. Every test builds its own uniquely named service, component and advisory, and reads only the
 * records of its own response or change.
 */
class DecisionTrailIntegrationTest extends AbstractInventoryIntegrationTest {

    private static final String TRAIL = "/api/audit/decisions";
    private static final String TICKETS = "/api/actions/risk-tickets";
    private static final String MEMOS = "/api/actions/regulator-memos";

    @Autowired private InventoryService inventory;
    @Autowired private AdvisoryStore advisories;
    @Autowired private FakeIssueTracker tracker;
    @Autowired private CurrentOrg currentOrg;

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
                .andExpect(jsonPath("$.content[*].subject").value(contains("RISK_TICKET", "RISK_TICKET",
                        "RISK_TICKET", "RISK_TICKET", "RISK_TICKET")))
                .andExpect(jsonPath("$.content[0].responseId").value(id))
                .andExpect(jsonPath("$.content[0].subjectId").value(id))
                .andExpect(jsonPath("$.content[0].serviceName").value("Trail " + f.tag()))
                .andExpect(jsonPath("$.content[0].changeTitle").value(containsString(f.advisory().canonicalId())))
                .andExpect(jsonPath("$.content[0].tier").value("HIGH"))
                .andExpect(jsonPath("$.content[0].score").isNumber())
                .andExpect(jsonPath("$.content[0].occurredAt").isNotEmpty())
                // The draft as drafted, with the evidence it was drafted on: the matching path and score arithmetic.
                .andExpect(jsonPath("$.content[0].state.summary").value(FakeAiDrafter.TICKET.summary()))
                .andExpect(jsonPath("$.content[0].state.status").value("PROPOSED"))
                .andExpect(jsonPath("$.content[0].state.evidence").value(hasItem(startsWith("Score calculation:"))))
                .andExpect(jsonPath("$.content[0].state.evidence").value(hasItem(startsWith("Source excerpt:"))))
                // The edit as made, the draft kept beside it.
                .andExpect(jsonPath("$.content[1].state.summary").value("Reviewed summary."))
                .andExpect(jsonPath("$.content[1].state.edit.draftedSummary").value(FakeAiDrafter.TICKET.summary()))
                .andExpect(jsonPath("$.content[1].reason").value(nullValue()))
                // The override with why, and Aegis's own assessment.
                .andExpect(jsonPath("$.content[2].reason").value("Behind the WAF"))
                .andExpect(jsonPath("$.content[2].state.priority").value("LOW"))
                .andExpect(jsonPath("$.content[2].state.dueDate").value(due.toString()))
                // The approval, then what it filed.
                .andExpect(jsonPath("$.content[3].state.status").value("FILING"))
                .andExpect(jsonPath("$.content[3].state.approval.by").value(reviewer.toString()))
                .andExpect(jsonPath("$.content[4].state.status").value("FILED"))
                .andExpect(jsonPath("$.content[4].state.issueKey").value(startsWith("AEG-")))
                .andReturn().getResponse().getContentAsString();
        // The override keeps Aegis's own priority: the drafted one.
        assertThat(JsonPath.<String>read(trail, "$.content[2].state.override.assessedPriority"))
                .isEqualTo(JsonPath.<String>read(trail, "$.content[0].state.priority"));
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

        mvc.perform(get(TRAIL).param("responseId", id).with(as("ANALYST", auditor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[*].action").value(
                        contains("DRAFTED", "APPROVED", "FILING_FAILED", "DISMISSED")))
                .andExpect(jsonPath("$.content[2].actorId").value(reviewer.toString()))
                .andExpect(jsonPath("$.content[2].reason").value(FakeIssueTracker.NOT_CONNECTED))
                .andExpect(jsonPath("$.content[2].state.status").value("FAILED"))
                .andExpect(jsonPath("$.content[3].actorId").value(dismisser.toString()))
                .andExpect(jsonPath("$.content[3].reason").value("Filed by hand as PAY-17"))
                .andExpect(jsonPath("$.content[3].state.status").value("DISMISSED"))
                .andExpect(jsonPath("$.content[3].state.approval.by").value(reviewer.toString()));
    }

    @Test
    void aRegulatorMemoIsRecordedOnItsChangeWithTheMostAffectedServicesScore() throws Exception {
        Fixture f = fixture();
        String created = mvc.perform(post(MEMOS).with(as("ANALYST", drafter)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeSignalId\":\"%s\"}".formatted(f.advisory().id())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String memo = JsonPath.read(created, "$.id");
        mvc.perform(post(MEMOS + "/{id}/approve", memo).with(as("ANALYST", reviewer)))
                .andExpect(status().isOk());

        mvc.perform(get(TRAIL).param("changeSignalId", f.advisory().id().toString())
                        .with(as("COMPLIANCE_ADMIN", auditor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].action").value(contains("DRAFTED", "APPROVED")))
                .andExpect(jsonPath("$.content[*].subject").value(contains("REGULATOR_MEMO", "REGULATOR_MEMO")))
                .andExpect(jsonPath("$.content[1].actorId").value(reviewer.toString()))
                .andExpect(jsonPath("$.content[1].responseId").value(memo))
                .andExpect(jsonPath("$.content[1].serviceId").value(nullValue()))
                .andExpect(jsonPath("$.content[1].tier").value("HIGH"))
                .andExpect(jsonPath("$.content[1].state.status").value("APPROVED"))
                .andExpect(jsonPath("$.content[1].state.body").value(containsString(f.advisory().canonicalId())))
                .andExpect(jsonPath("$.content[1].state.services[0].name").value("Trail " + f.tag()));
    }

    @Test
    void onlyGovernanceReadsTheTrailAndOnlyItsOwnOrganizations() throws Exception {
        Fixture f = fixture();
        String id = draftTicket(f);

        mvc.perform(get(TRAIL).param("responseId", id).with(as("SERVICE_OWNER", reviewer)))
                .andExpect(status().isForbidden());
        mvc.perform(get(TRAIL).param("responseId", id)).andExpect(status().isUnauthorized());
        mvc.perform(get(TRAIL).param("responseId", id).with(as("COMPLIANCE_ADMIN", auditor)))
                .andExpect(jsonPath("$.totalElements").value(1));

        Exception[] failed = new Exception[1];
        currentOrg.runAs(UUID.randomUUID(), () -> {
            try {
                mvc.perform(get(TRAIL).param("responseId", id).with(as("COMPLIANCE_ADMIN", auditor)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalElements").value(0));
            } catch (Exception e) {
                failed[0] = e;
            }
        });
        if (failed[0] != null) {
            throw failed[0];
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private String draftTicket(Fixture f) throws Exception {
        String created = mvc.perform(post(TICKETS).with(as("ANALYST", drafter)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeSignalId\":\"%s\",\"serviceId\":\"%s\"}"
                                .formatted(f.advisory().id(), f.service().getId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.id");
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
                List.of(AffectedProduct.ofPackage("Maven", "com.acme:" + artifact, null, "1.0.0", null, null, "1.1.0"))));
        return new Fixture(tag, service, advisory);
    }

    private record Fixture(String tag, BusinessService service, Advisory advisory) {
    }

    private static RequestPostProcessor as(String role, UUID userId) {
        return jwt().jwt(token -> token.subject(userId.toString())
                        .claim("org", DemoEstateSeeder.DEMO_ORG_ID.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
