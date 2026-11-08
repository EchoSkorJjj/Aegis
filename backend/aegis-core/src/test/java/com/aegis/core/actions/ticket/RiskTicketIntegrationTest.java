package com.aegis.core.actions.ticket;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aegis.core.actions.tracker.IssueTracker.Block;
import com.aegis.core.actions.tracker.IssueTracker.IssueDraft;
import com.aegis.core.actions.tracker.IssueTracker.Priority;
import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.incidents.VendorIncident;
import com.aegis.core.incidents.VendorIncidentStore;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.tenant.CurrentOrg;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import com.aegis.core.support.FakeAiDrafter;
import com.aegis.core.support.FakeIssueTracker;
import com.aegis.core.support.TestOwners;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Risk tickets end to end (E8 story 5): drafted from a change's impact on one service and filed only when
 * approved, against the in-memory tracker and AI drafter the suite shares. These services link no repository,
 * so no upgrade pull request goes with the ticket: it only records the risk, and its drafter may file it (the
 * four-eyes rule for responses with a pull request is UpgradeProposalIntegrationTest's). Every test builds its
 * own uniquely named service, component and advisory.
 */
class RiskTicketIntegrationTest extends AbstractInventoryIntegrationTest {

    private static final String PATH = "/api/actions/risk-tickets";

    @Autowired private InventoryService inventory;
    @Autowired private AdvisoryStore advisories;
    @Autowired private VendorIncidentStore incidents;
    @Autowired private TestOwners owners;
    @Autowired private FakeIssueTracker tracker;
    @Autowired private FakeAiDrafter drafter;
    @Autowired private CurrentOrg currentOrg;
    @Autowired private RiskTicketService tickets;
    @Autowired private RiskTicketStore ticketStore;

    private final UUID analyst = UUID.randomUUID();
    private final UUID approver = UUID.randomUUID();

    @AfterEach
    void reconnect() {
        tracker.connected(true);
        drafter.unavailable(false);
    }

    @Test
    void fallbackDraftsAndTheirStoredEvidenceRedactPersonalAndSecretData() throws Exception {
        Fixture f = fixture();
        Advisory old = f.advisory();
        advisories.upsert(new Advisory(old.id(), old.sourceId(), old.externalId(), old.canonicalId(),
                "Affected library; contact alice@example.test; password=opaque-credential", old.publishedAt(),
                Instant.now(), old.cvss(), old.affected()));
        Owner owner = owners.assign(f.service().getId(), "Personal Owner " + f.tag());
        drafter.unavailable(true);

        String created = mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                        .content(request(f.advisory().id(), f.service().getId())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.aiDrafted").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(created).contains("[REDACTED]", "1 assigned (personal details omitted)")
                .doesNotContain("alice@example.test", "opaque-credential", owner.getName(), owner.getUserId().toString());
        String id = JsonPath.read(created, "$.id");
        String stored = mvc.perform(get(PATH + "/{id}", id).with(as("COMPLIANCE_ADMIN", approver)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(stored).doesNotContain("alice@example.test", "opaque-credential", owner.getName());
        assertThat(drafter.requests()).filteredOn(sent -> sent.findingId().equals(id)).hasSize(1).allSatisfy(sent ->
                assertThat(sent.context()).doesNotContain("alice@example.test", "opaque-credential", owner.getName()));
        assertThat(tracker.filed()).noneMatch(issue -> issue.title().contains(f.cve()));
    }

    @Test
    void aTicketIsDraftedFromTheImpactAndFiledOnlyWhenApproved() throws Exception {
        Fixture f = fixture();
        long before = ticketStore.countPending();

        String created = mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                        .content(request(f.advisory().id(), f.service().getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.signalType").value("VULNERABILITY"))
                .andExpect(jsonPath("$.title").value(f.cve() + " affects " + f.service().getName()))
                .andExpect(jsonPath("$.serviceName").value(f.service().getName()))
                .andExpect(jsonPath("$.labels[3]").value(f.cve()))
                .andExpect(jsonPath("$.summary").value(FakeAiDrafter.TICKET.summary()))
                .andExpect(jsonPath("$.actions[0]").value("Upgrade the library."))
                .andExpect(jsonPath("$.evidence[0]").value(containsString(f.component().getName())))
                .andExpect(jsonPath("$.evidence[1]").value(startsWith("Impact score ")))
                .andExpect(jsonPath("$.evidence").value(hasItem("Not automated: Link the service's repository so Aegis can "
                        + "draft the upgrade pull request for " + f.component().getName())))
                .andExpect(jsonPath("$.evidence").value(hasItem(startsWith("Source: osv / "))))
                .andExpect(jsonPath("$.evidence").value(hasItem("Source excerpt: A flaw in ticket-lib.")))
                .andExpect(jsonPath("$.evidence").value(hasItem(startsWith("Score calculation: severity "))))
                .andExpect(jsonPath("$.pullRequests").isEmpty())
                .andExpect(jsonPath("$.aiDrafted").value(true))
                .andExpect(jsonPath("$.citations[0]").value("C1 quoted context"))
                .andExpect(jsonPath("$.createdBy").value(analyst.toString()))
                .andExpect(jsonPath("$.serviceNow.cmdb_ci").value(f.service().getName()))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        ImpactTier tier = ImpactTier.valueOf(JsonPath.read(created, "$.tier"));
        assertThat(Priority.valueOf(JsonPath.read(created, "$.priority"))).isEqualTo(RiskTicketText.priority(tier));
        assertThat(drafter.requests()).anySatisfy(sent -> {
            assertThat(sent.findingId()).isEqualTo(id);
            assertThat(sent.draftType()).isEqualTo("risk_ticket");
            assertThat(sent.context()).contains("A flaw in ticket-lib.").contains("Affected service: " + f.service().getName());
        });
        assertThat(tracker.filed()).as("nothing is filed before it is approved")
                .noneMatch(issue -> issue.title().contains(f.cve()));
        assertThat(ticketStore.countPending()).isEqualTo(before + 1);
        currentOrg.runAs(UUID.randomUUID(), () -> assertThat(ticketStore.countPending()).isZero());

        // With no pull request it only records the risk, so its drafter files it.
        mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", analyst)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILED"))
                .andExpect(jsonPath("$.decidedBy").value(analyst.toString()))
                .andExpect(jsonPath("$.issueKey").value(startsWith("AEG-")))
                .andExpect(jsonPath("$.issueUrl").value(startsWith("https://tracker.example/browse/AEG-")));

        IssueDraft issue = tracker.filed().stream().filter(filed -> filed.title().contains(f.cve())).findFirst()
                .orElseThrow();
        assertThat(ticketStore.countPending()).isEqualTo(before);
        assertThat(issue.priority()).isEqualTo(RiskTicketText.priority(tier));
        assertThat(issue.labels()).contains("aegis", f.cve());
        assertThat(issue.description()).extracting(Block::text).contains("Recommended actions", "Evidence");
    }

    @Test
    void analystsAndTheServicesOwnersDraftAndDecideWhileComplianceOnlyAudits() throws Exception {
        Fixture f = fixture();
        Owner owner = owners.assign(f.service().getId(), "Ticket owner " + f.tag());
        Owner coOwner = owners.assign(f.service().getId(), "Ticket co-owner " + f.tag());
        UUID stranger = UUID.randomUUID();
        String body = request(f.advisory().id(), f.service().getId());

        for (String role : new String[]{"SERVICE_OWNER", "COMPLIANCE_ADMIN", "USER"}) {
            mvc.perform(post(PATH).with(as(role, stranger)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
        String id = id(mvc.perform(post(PATH).with(as("SERVICE_OWNER", owner.getUserId()))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()));

        mvc.perform(get(PATH + "/{id}", id).with(as("COMPLIANCE_ADMIN", stranger))).andExpect(status().isOk());
        for (RequestPostProcessor outsider : List.of(as("SERVICE_OWNER", stranger), as("COMPLIANCE_ADMIN", stranger))) {
            mvc.perform(post(PATH + "/{id}/approve", id).with(outsider)).andExpect(status().isForbidden());
            mvc.perform(post(PATH + "/{id}/dismiss", id).with(outsider).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"not mine\"}")).andExpect(status().isForbidden());
        }
        mvc.perform(post(PATH + "/{id}/approve", id).with(as("SERVICE_OWNER", coOwner.getUserId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILED"))
                .andExpect(jsonPath("$.decidedBy").value(coOwner.getUserId().toString()));
    }

    @Test
    void anAiOutageNeverBlocksATicketItFallsBackToAegisOwnFacts() throws Exception {
        Fixture f = fixture();
        drafter.unavailable(true);

        mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                        .content(request(f.advisory().id(), f.service().getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.aiDrafted").value(false))
                .andExpect(jsonPath("$.summary").value(f.cve() + " affects " + f.service().getName()))
                .andExpect(jsonPath("$.risk").value("A flaw in ticket-lib."))
                .andExpect(jsonPath("$.actions[1]").value("Upgrade or mitigate each affected component."))
                .andExpect(jsonPath("$.citations").isEmpty());
    }

    @Test
    void aVendorOutageIsTicketedForTheServicesThatDependOnTheVendor() throws Exception {
        String tag = tag();
        BusinessService service = inventory.createBusinessService("Outage " + tag, BusinessCriticality.CRITICAL,
                ServiceExposure.INTERNET_FACING);
        ThirdPartyVendor vendor = inventory.createThirdPartyVendor("Acme Pay " + tag);
        inventory.addServiceVendor(service.getId(), vendor.getId());
        VendorIncident incident = incidents.upsert(new VendorIncident(null, "vendor-status", "acme:incident-" + tag,
                "acme-pay-" + tag, "Acme Pay " + tag, "Card API outage", "investigating", "major", null,
                "Elevated card declines", Instant.now(), null, null));

        mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                        .content(request(incident.id(), service.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.signalType").value("VENDOR_OUTAGE"))
                .andExpect(jsonPath("$.title").value("Vendor outage affects " + service.getName() + ": Card API outage"))
                .andExpect(jsonPath("$.labels[1]").value("vendor-outage"));
        assertThat(drafter.requests()).anySatisfy(sent -> assertThat(sent.context())
                .startsWith("Vendor outage Card API outage at Acme Pay " + tag));
    }

    @Test
    void aLiveTicketIsNotDraftedTwiceButADismissedOneFreesTheSlot() throws Exception {
        Fixture f = fixture();
        String body = request(f.advisory().id(), f.service().getId());
        String id = id(mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isCreated()));

        mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("already proposed")));
        mvc.perform(post(PATH + "/{id}/dismiss", id).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\" Tracked in the platform migration \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"))
                .andExpect(jsonPath("$.dismissReason").value("Tracked in the platform migration"));
        mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(get(PATH).param("serviceId", f.service().getId().toString()).param("status", "DISMISSED")
                        .with(as("SERVICE_OWNER", UUID.randomUUID())))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].serviceNow").doesNotExist());
    }

    @Test
    void aTicketTheTrackerRefusesIsRecordedAndCanBeApprovedAgain() throws Exception {
        Fixture f = fixture();
        String id = id(mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                .content(request(f.advisory().id(), f.service().getId()))).andExpect(status().isCreated()));

        tracker.connected(false);
        mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", approver))).andExpect(status().isConflict());
        mvc.perform(get(PATH + "/{id}", id).with(as("ANALYST", approver)))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureMessage").value(FakeIssueTracker.NOT_CONNECTED));

        tracker.connected(true);
        mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", approver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILED"))
                .andExpect(jsonPath("$.failureMessage").value(nullValue()));
    }

    @Test
    void aServiceTheChangeDoesNotReachGetsNoTicket() throws Exception {
        Fixture f = fixture();
        BusinessService unrelated = inventory.createBusinessService("Unrelated " + f.tag(), BusinessCriticality.LOW,
                ServiceExposure.INTERNAL);
        mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                        .content(request(f.advisory().id(), unrelated.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This change does not reach the service"));
    }

    @Test
    void anotherOrganizationSeesNoneOfIt() throws Exception {
        Fixture f = fixture();
        UUID id = UUID.fromString(id(mvc.perform(post(PATH).with(as("ANALYST", analyst))
                .contentType(MediaType.APPLICATION_JSON).content(request(f.advisory().id(), f.service().getId())))
                .andExpect(status().isCreated())));

        Throwable[] seen = new Throwable[1];
        long[] listed = {-1};
        currentOrg.runAs(UUID.randomUUID(), () -> {
            listed[0] = tickets.list(null, null, null, null, Pageable.unpaged()).getTotalElements();
            try {
                tickets.get(id);
            } catch (Throwable failure) {
                seen[0] = failure;
            }
        });
        assertThat(seen[0]).isInstanceOf(ApiExceptions.NotFound.class);
        assertThat(listed[0]).isZero();
    }

    // ---- fixture -----------------------------------------------------------------------------------

    /** A high-criticality, internet-facing service directly using one Maven component an advisory affects. */
    private Fixture fixture() {
        String tag = tag();
        String artifact = "ticket-lib-" + tag;
        BusinessService service = inventory.createBusinessService("Tickets " + tag, BusinessCriticality.HIGH,
                ServiceExposure.INTERNET_FACING);
        SoftwareComponent component = inventory.createSoftwareComponent("maven", "com.acme:" + artifact, "1.0.0");
        inventory.addServiceComponent(service.getId(), component.getId());
        Advisory advisory = advisories.upsert(new Advisory(null, "osv", "GHSA-" + tag, "CVE-2099-" + tag,
                "A flaw in ticket-lib.", Instant.now(), Instant.now(),
                new Advisory.Cvss("3.1", null, new BigDecimal("9.1"), "CRITICAL"),
                List.of(AffectedProduct.ofPackage("Maven", "com.acme:" + artifact, null, "1.0.0", null, null, "1.1.0"))));
        return new Fixture(tag, service, component, advisory);
    }

    private record Fixture(String tag, BusinessService service, SoftwareComponent component, Advisory advisory) {
        String cve() {
            return advisory.canonicalId();
        }
    }

    private static String tag() {
        return Long.toHexString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
    }

    private static String request(UUID changeSignalId, UUID serviceId) {
        return "{\"changeSignalId\":\"%s\",\"serviceId\":\"%s\"}".formatted(changeSignalId, serviceId);
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static RequestPostProcessor as(String role, UUID userId) {
        return jwt().jwt(token -> token.subject(userId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
