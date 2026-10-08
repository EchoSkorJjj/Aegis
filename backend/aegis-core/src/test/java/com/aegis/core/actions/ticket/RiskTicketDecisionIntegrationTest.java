package com.aegis.core.actions.ticket;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.aegis.core.actions.tracker.IssueTracker.Block;
import com.aegis.core.actions.tracker.IssueTracker.IssueDraft;
import com.aegis.core.actions.tracker.IssueTracker.Priority;
import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.demo.DemoEstateSeeder;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import com.aegis.core.support.FakeAiDrafter;
import com.aegis.core.support.FakeIssueTracker;
import com.aegis.core.support.Race;
import com.aegis.core.support.TestOwners;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deciding a response (E9 story 3): a reviewer edits the drafted narrative or overrides Aegis's priority and due
 * date before approving or dismissing it, each on record; dismissing and overriding need a reason; a decision made
 * on an outdated view, or at the same moment as someone else's, is a conflict; and the item moves New → In review
 * → Approved or Overridden → Actioned or Closed. The services link no repository, so each response is the ticket
 * alone (the four-eyes rule for a response with a pull request is UpgradeProposalIntegrationTest's). Every test
 * builds its own uniquely named service, component and advisory.
 */
class RiskTicketDecisionIntegrationTest extends AbstractInventoryIntegrationTest {

    private static final String PATH = "/api/actions/risk-tickets";

    @Autowired private InventoryService inventory;
    @Autowired private AdvisoryStore advisories;
    @Autowired private TestOwners owners;
    @Autowired private FakeIssueTracker tracker;

    private final UUID drafter = UUID.randomUUID();
    private final UUID reviewer = UUID.randomUUID();

    @AfterEach
    void reconnect() {
        tracker.connected(true);
    }

    @Test
    void aReviewerEditsTheDraftBeforeApprovingItAndTheDraftStaysOnRecord() throws Exception {
        Fixture f = fixture();
        String id = draft(f);

        String edited = mvc.perform(post(PATH + "/{id}/edit", id).with(as("ANALYST", reviewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edit(0, "  Reviewed summary.  ", "Reviewed risk.", "Reviewed impact.",
                                "Upgrade   the library this sprint.", " ", "Confirm with the vendor.")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("Reviewed summary."))
                .andExpect(jsonPath("$.actions.length()").value(2))
                .andExpect(jsonPath("$.actions[0]").value("Upgrade the library this sprint."))
                .andExpect(jsonPath("$.edit.draftedSummary").value(FakeAiDrafter.TICKET.summary()))
                .andExpect(jsonPath("$.edit.draftedActions[0]").value("Upgrade the library."))
                .andExpect(jsonPath("$.edit.by").value(reviewer.toString()))
                .andExpect(jsonPath("$.aiDrafted").value(true))
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.stage").value("IN_REVIEW"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(edited).doesNotContain("\"override\":{");

        // A second edit keeps the draft as it first was, and records who edited it last.
        mvc.perform(post(PATH + "/{id}/edit", id).with(as("ANALYST", drafter)).contentType(MediaType.APPLICATION_JSON)
                        .content(edit(1, "Final summary.", "Reviewed risk.", "Reviewed impact.", "Upgrade.")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("Final summary."))
                .andExpect(jsonPath("$.edit.draftedSummary").value(FakeAiDrafter.TICKET.summary()))
                .andExpect(jsonPath("$.edit.by").value(drafter.toString()));
        // Nothing changed, or a section left empty, is refused.
        mvc.perform(post(PATH + "/{id}/edit", id).with(as("ANALYST", reviewer)).contentType(MediaType.APPLICATION_JSON)
                        .content(edit(2, "Final summary.", "Reviewed risk.", "Reviewed impact.", "Upgrade.")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Nothing was changed"));
        mvc.perform(post(PATH + "/{id}/edit", id).with(as("ANALYST", reviewer)).contentType(MediaType.APPLICATION_JSON)
                        .content(edit(2, "Final summary.", " ", "Reviewed impact.", "Upgrade.")))
                .andExpect(status().isBadRequest());

        mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", reviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILED"))
                .andExpect(jsonPath("$.stage").value("ACTIONED"))
                .andExpect(jsonPath("$.approval.by").value(reviewer.toString()));
        IssueDraft issue = filed(f);
        assertThat(issue.description()).extracting(Block::text)
                .contains("Final summary.")
                .doesNotContain(FakeAiDrafter.TICKET.summary())
                .anySatisfy(text -> assertThat(text).contains("an AI draft a person reviewed and edited"));
        assertThat(issue.description()).flatExtracting(Block::items).contains("Upgrade.");
    }

    @Test
    void overridingNeedsAReasonFilesTheReviewersPriorityAndKeepsAegisOwnAssessment() throws Exception {
        Fixture f = fixture();
        String id = draft(f);
        String drafted = mvc.perform(get(PATH + "/{id}", id).with(as("ANALYST", reviewer)))
                .andReturn().getResponse().getContentAsString();
        Priority assessed = Priority.valueOf(JsonPath.read(drafted, "$.priority"));
        String assessedDue = JsonPath.read(drafted, "$.dueDate");
        LocalDate due = LocalDate.now(ZoneOffset.UTC).plusDays(60);

        for (String noReason : List.of("", "   ")) {
            mvc.perform(post(PATH + "/{id}/override", id).with(as("ANALYST", reviewer))
                            .contentType(MediaType.APPLICATION_JSON).content(override(0, Priority.LOW, due, noReason)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(PATH + "/{id}/override", id).with(as("ANALYST", reviewer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"priority\":\"LOW\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PATH + "/{id}/override", id).with(as("ANALYST", reviewer)).contentType(MediaType.APPLICATION_JSON)
                        .content(override(0, Priority.LOW, LocalDate.now(ZoneOffset.ofHours(-12)).minusDays(1),
                                "Too late")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The due date can't be in the past"));
        mvc.perform(post(PATH + "/{id}/override", id).with(as("ANALYST", reviewer)).contentType(MediaType.APPLICATION_JSON)
                        .content(override(0, assessed, LocalDate.parse(assessedDue), "Same as Aegis")))
                .andExpect(status().isBadRequest());

        mvc.perform(post(PATH + "/{id}/override", id).with(as("ANALYST", reviewer)).contentType(MediaType.APPLICATION_JSON)
                        .content(override(0, Priority.LOW, due, " The vulnerable endpoint is switched off. ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("LOW"))
                .andExpect(jsonPath("$.dueDate").value(due.toString()))
                .andExpect(jsonPath("$.override.assessedPriority").value(assessed.name()))
                .andExpect(jsonPath("$.override.assessedDueDate").value(assessedDue))
                .andExpect(jsonPath("$.override.reason").value("The vulnerable endpoint is switched off."))
                .andExpect(jsonPath("$.override.by").value(reviewer.toString()))
                .andExpect(jsonPath("$.stage").value("IN_REVIEW"));
        // Overriding again keeps Aegis's assessment, not the previous override; no due date is none.
        mvc.perform(post(PATH + "/{id}/override", id).with(as("ANALYST", reviewer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"priority\":\"LOWEST\",\"reason\":\"Accepted risk until Q1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("LOWEST"))
                .andExpect(jsonPath("$.dueDate").doesNotExist())
                .andExpect(jsonPath("$.override.assessedPriority").value(assessed.name()))
                .andExpect(jsonPath("$.override.reason").value("Accepted risk until Q1"));

        mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", reviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILED"))
                .andExpect(jsonPath("$.stage").value("ACTIONED"))
                .andExpect(jsonPath("$.override.reason").value("Accepted risk until Q1"));
        IssueDraft issue = filed(f);
        assertThat(issue.priority()).isEqualTo(Priority.LOWEST);
        assertThat(issue.dueDate()).isNull();
        assertThat(issue.description()).extracting(Block::text).anySatisfy(text -> assertThat(text)
                .contains("Set by the reviewer: lowest priority, no due date")
                .contains("Aegis assessed " + assessed.name().toLowerCase())
                .contains("Why: Accepted risk until Q1"));
    }

    @Test
    void anOverrideCanChangeItsReasonOrBeWithdrawnBackToAegisOwnAssessment() throws Exception {
        Fixture f = fixture();
        String id = draft(f);
        String drafted = mvc.perform(get(PATH + "/{id}", id).with(as("ANALYST", reviewer)))
                .andReturn().getResponse().getContentAsString();
        Priority assessed = Priority.valueOf(JsonPath.read(drafted, "$.priority"));
        LocalDate assessedDue = LocalDate.parse(JsonPath.read(drafted, "$.dueDate"));

        mvc.perform(post(PATH + "/{id}/override/withdraw", id).with(as("ANALYST", reviewer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PATH + "/{id}/override", id).with(as("ANALYST", reviewer)).contentType(MediaType.APPLICATION_JSON)
                        .content(override(0, Priority.LOW, null, "Mitigated by the WAF rule")))
                .andExpect(status().isOk());

        // The reason alone may change; the same override again, or Aegis's own values, is refused.
        mvc.perform(post(PATH + "/{id}/override", id).with(as("ANALYST", reviewer)).contentType(MediaType.APPLICATION_JSON)
                        .content(override(1, Priority.LOW, null, "Mitigated by the WAF rule")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Nothing was changed"));
        mvc.perform(post(PATH + "/{id}/override", id).with(as("ANALYST", reviewer)).contentType(MediaType.APPLICATION_JSON)
                        .content(override(1, Priority.LOW, null, "The WAF rule blocks the exploit path")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.override.reason").value("The WAF rule blocks the exploit path"))
                .andExpect(jsonPath("$.override.assessedPriority").value(assessed.name()));
        mvc.perform(post(PATH + "/{id}/override", id).with(as("ANALYST", reviewer)).contentType(MediaType.APPLICATION_JSON)
                        .content(override(2, assessed, assessedDue, "Aegis was right")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("withdraw the override instead")));

        // Withdrawn: Aegis's own priority and due date again, and the ticket is filed as Aegis assessed it.
        mvc.perform(post(PATH + "/{id}/override/withdraw", id).with(as("ANALYST", reviewer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value(assessed.name()))
                .andExpect(jsonPath("$.dueDate").value(assessedDue.toString()))
                .andExpect(jsonPath("$.override").doesNotExist())
                .andExpect(jsonPath("$.stage").value("IN_REVIEW"));
        mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", reviewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("ACTIONED"));
        IssueDraft issue = filed(f);
        assertThat(issue.priority()).isEqualTo(assessed);
        assertThat(issue.dueDate()).isEqualTo(assessedDue);
        assertThat(issue.description()).extracting(Block::text).doesNotContain("Priority and due date");
    }

    @Test
    void aReasonStaysWithinItsColumnOnceSensitiveDetailsAreRemoved() throws Exception {
        // Six-character addresses within the limit, each of which redaction turns into ten characters.
        String reason = "a@b.co ".repeat(142).strip();
        assertThat(reason).hasSizeLessThanOrEqualTo(RiskTicketService.MAX_REASON);
        String cut = "[REDACTED]";

        mvc.perform(post(PATH + "/{id}/override", draft(fixture())).with(as("ANALYST", reviewer))
                        .contentType(MediaType.APPLICATION_JSON).content(override(0, Priority.LOW, null, reason)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.override.reason",
                        allOf(hasLength(RiskTicketService.MAX_REASON), startsWith(cut), endsWith("…"))));
        mvc.perform(post(PATH + "/{id}/dismiss", draft(fixture())).with(as("ANALYST", reviewer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dismissReason",
                        allOf(hasLength(RiskTicketService.MAX_REASON), startsWith(cut), endsWith("…"))));
    }

    @Test
    void dismissingAResponseWhoseFilingFailedKeepsWhoApprovedIt() throws Exception {
        String id = draft(fixture());
        tracker.connected(false);
        mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", reviewer)))
                .andExpect(status().isConflict());

        UUID dismisser = UUID.randomUUID();
        mvc.perform(post(PATH + "/{id}/dismiss", id).with(as("ANALYST", dismisser))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Filed by hand as PAY-232\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"))
                .andExpect(jsonPath("$.stage").value("CLOSED"))
                .andExpect(jsonPath("$.decidedBy").value(dismisser.toString()))
                .andExpect(jsonPath("$.approval.by").value(reviewer.toString()))
                .andExpect(jsonPath("$.failureMessage").isNotEmpty());
    }

    @Test
    void dismissingNeedsAReason() throws Exception {
        String id = draft(fixture());
        for (String body : List.of("{}", "{\"reason\":\"\"}", "{\"reason\":\"   \"}")) {
            mvc.perform(post(PATH + "/{id}/dismiss", id).with(as("ANALYST", reviewer))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get(PATH + "/{id}", id).with(as("ANALYST", reviewer)))
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.stage").value("IN_REVIEW"));
    }

    @Test
    void anItemMovesFromInReviewThroughApprovedOrOverriddenToActionedOrClosed() throws Exception {
        // Approved as drafted: the filing fails, the decision stands (Approved), and the retry actions it.
        String approved = draft(fixture());
        tracker.connected(false);
        mvc.perform(post(PATH + "/{id}/approve", approved).with(as("ANALYST", reviewer)))
                .andExpect(status().isConflict());
        mvc.perform(get(PATH + "/{id}", approved).with(as("ANALYST", reviewer)))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.stage").value("APPROVED"));

        // Overridden, then approved: Overridden until the ticket is filed.
        String overridden = draft(fixture());
        mvc.perform(post(PATH + "/{id}/override", overridden).with(as("ANALYST", reviewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(override(0, Priority.LOW, null, "Mitigated by the WAF rule")))
                .andExpect(status().isOk());
        mvc.perform(post(PATH + "/{id}/approve", overridden).with(as("ANALYST", reviewer)))
                .andExpect(status().isConflict());
        mvc.perform(get(PATH + "/{id}", overridden).with(as("ANALYST", reviewer)))
                .andExpect(jsonPath("$.stage").value("OVERRIDDEN"));

        tracker.connected(true);
        for (String id : List.of(approved, overridden)) {
            mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", reviewer)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stage").value("ACTIONED"));
        }

        // Dismissed: closed, whether still in review or approved and failed.
        Fixture closing = fixture();
        String dismissed = draft(closing);
        mvc.perform(post(PATH + "/{id}/dismiss", dismissed).with(as("ANALYST", reviewer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Already tracked in PAY-231\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("CLOSED"));
        mvc.perform(get(PATH).param("serviceId", closing.service().getId().toString()).with(as("ANALYST", reviewer)))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].stage").value("CLOSED"));
    }

    @Test
    void onlyAResponseAwaitingADecisionCanBeEditedOrOverridden() throws Exception {
        String filed = draft(fixture());
        mvc.perform(post(PATH + "/{id}/approve", filed).with(as("ANALYST", reviewer))).andExpect(status().isOk());
        String dismissed = draft(fixture());
        mvc.perform(post(PATH + "/{id}/dismiss", dismissed).with(as("ANALYST", reviewer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Not needed\"}")).andExpect(status().isOk());

        for (String id : List.of(filed, dismissed)) {
            long version = version(id);
            mvc.perform(post(PATH + "/{id}/edit", id).with(as("ANALYST", reviewer)).contentType(MediaType.APPLICATION_JSON)
                            .content(edit(version, "Late summary.", "Risk.", "Impact.", "Act.")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(containsString("only one awaiting a decision can")));
            mvc.perform(post(PATH + "/{id}/override", id).with(as("ANALYST", reviewer))
                            .contentType(MediaType.APPLICATION_JSON).content(override(version, Priority.LOW, null, "Late")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(containsString("only one awaiting a decision can")));
        }
    }

    @Test
    void aDecisionOnAResponseThatChangedSinceItWasReadIsAConflictAndChangesNothing() throws Exception {
        Fixture f = fixture();
        String id = draft(f);
        // Someone edits it while another reviewer still has version 0 open.
        mvc.perform(post(PATH + "/{id}/edit", id).with(as("ANALYST", drafter)).contentType(MediaType.APPLICATION_JSON)
                        .content(edit(0, "Changed meanwhile.", "Risk.", "Impact.", "Act.")))
                .andExpect(status().isOk());

        String stale = "This response changed after you opened it: someone else edited or decided it. Reload it "
                + "and decide again";
        mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", reviewer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(stale));
        mvc.perform(post(PATH + "/{id}/dismiss", id).with(as("ANALYST", reviewer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Not needed\",\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(stale));
        mvc.perform(post(PATH + "/{id}/edit", id).with(as("ANALYST", reviewer)).contentType(MediaType.APPLICATION_JSON)
                        .content(edit(0, "My edit.", "Risk.", "Impact.", "Act.")))
                .andExpect(status().isConflict());
        mvc.perform(post(PATH + "/{id}/override", id).with(as("ANALYST", reviewer)).contentType(MediaType.APPLICATION_JSON)
                        .content(override(0, Priority.LOW, null, "Mine")))
                .andExpect(status().isConflict());
        mvc.perform(get(PATH + "/{id}", id).with(as("ANALYST", reviewer)))
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.summary").value("Changed meanwhile."))
                .andExpect(jsonPath("$.override").doesNotExist())
                .andExpect(jsonPath("$.version").value(1));
        assertThat(tracker.filed()).noneMatch(issue -> issue.title().contains(f.cve()));

        // With the current version, the decision goes through.
        mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", reviewer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILED"));
    }

    @Test
    void twoPeopleDecidingAtOnceGetOneOutcomeAndAConflict() throws Exception {
        for (int round = 0; round < 3; round++) {
            Fixture f = fixture();
            String id = draft(f);
            UUID other = UUID.randomUUID();
            List<MockHttpServletResponse> responses = Race.race(
                    () -> mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", reviewer))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                            .andReturn().getResponse(),
                    () -> mvc.perform(post(PATH + "/{id}/dismiss", id).with(as("ANALYST", other))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"No need\",\"version\":0}"))
                            .andReturn().getResponse());

            List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).sorted().toList();
            assertThat(statuses).containsExactly(200, 409);
            MockHttpServletResponse lost = responses.stream().filter(r -> r.getStatus() == 409).findFirst().orElseThrow();
            assertThat(JsonPath.<String>read(lost.getContentAsString(), "$.message")).isNotBlank();

            String winner = responses.stream().filter(r -> r.getStatus() == 200).findFirst().orElseThrow()
                    .getContentAsString();
            String outcome = JsonPath.read(winner, "$.status");
            mvc.perform(get(PATH + "/{id}", id).with(as("ANALYST", reviewer)))
                    .andExpect(jsonPath("$.status").value(outcome));
            long issues = tracker.filed().stream().filter(issue -> issue.title().contains(f.cve())).count();
            assertThat(issues).isEqualTo(outcome.equals("FILED") ? 1 : 0);
        }
    }

    @Test
    void analystsAndTheServicesOwnersEditAndOverrideWhileComplianceOnlyAudits() throws Exception {
        Fixture f = fixture();
        Owner owner = owners.assign(f.service().getId(), "Decision owner " + f.tag());
        String id = draft(f);
        UUID stranger = UUID.randomUUID();

        for (RequestPostProcessor outsider : List.of(as("SERVICE_OWNER", stranger), as("COMPLIANCE_ADMIN", stranger),
                as("USER", stranger))) {
            mvc.perform(post(PATH + "/{id}/edit", id).with(outsider).contentType(MediaType.APPLICATION_JSON)
                    .content(edit(0, "Mine.", "Risk.", "Impact.", "Act."))).andExpect(status().isForbidden());
            mvc.perform(post(PATH + "/{id}/override", id).with(outsider).contentType(MediaType.APPLICATION_JSON)
                    .content(override(0, Priority.LOW, null, "Mine"))).andExpect(status().isForbidden());
        }
        mvc.perform(post(PATH + "/{id}/override", id).with(as("SERVICE_OWNER", owner.getUserId()))
                        .contentType(MediaType.APPLICATION_JSON).content(override(0, Priority.LOW, null, "Owner's call")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.override.by").value(owner.getUserId().toString()));
        mvc.perform(get(PATH + "/{id}", id).with(as("COMPLIANCE_ADMIN", stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.override.reason").value("Owner's call"));
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private String draft(Fixture f) throws Exception {
        String created = mvc.perform(post(PATH).with(as("ANALYST", drafter)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeSignalId\":\"%s\",\"serviceId\":\"%s\"}"
                                .formatted(f.advisory().id(), f.service().getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stage").value("IN_REVIEW"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.id");
    }

    private long version(String id) throws Exception {
        String ticket = mvc.perform(get(PATH + "/{id}", id).with(as("ANALYST", reviewer)))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(ticket, "$.version")).longValue();
    }

    private IssueDraft filed(Fixture f) {
        return tracker.filed().stream().filter(issue -> issue.title().contains(f.cve())).findFirst().orElseThrow();
    }

    private static String edit(long version, String summary, String risk, String impact, String... actions) {
        StringBuilder list = new StringBuilder();
        for (String action : actions) {
            list.append(list.isEmpty() ? "" : ",").append('"').append(action).append('"');
        }
        return "{\"version\":%d,\"summary\":\"%s\",\"risk\":\"%s\",\"impact\":\"%s\",\"actions\":[%s]}"
                .formatted(version, summary, risk, impact, list);
    }

    private static String override(long version, Priority priority, LocalDate due, String reason) {
        return "{\"version\":%d,\"priority\":\"%s\",%s\"reason\":\"%s\"}".formatted(version, priority,
                due == null ? "" : "\"dueDate\":\"" + due + "\",", reason);
    }

    /** A high-criticality, internet-facing service directly using one Maven component an advisory affects. */
    private Fixture fixture() {
        String tag = Long.toHexString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        String artifact = "decision-lib-" + tag;
        BusinessService service = inventory.createBusinessService("Decisions " + tag, BusinessCriticality.HIGH,
                ServiceExposure.INTERNET_FACING);
        SoftwareComponent component = inventory.createSoftwareComponent("maven", "com.acme:" + artifact, "1.0.0");
        inventory.addServiceComponent(service.getId(), component.getId());
        Advisory advisory = advisories.upsert(new Advisory(null, "osv", "GHSA-" + tag, "CVE-2098-" + tag,
                "A flaw in decision-lib.", Instant.now(), Instant.now(),
                new Advisory.Cvss("3.1", null, new BigDecimal("9.1"), "CRITICAL"),
                List.of(AffectedProduct.ofPackage("Maven", "com.acme:" + artifact, null, "1.0.0", null, null, "1.1.0"))));
        return new Fixture(tag, service, advisory);
    }

    private record Fixture(String tag, BusinessService service, Advisory advisory) {
        String cve() {
            return advisory.canonicalId();
        }
    }

    /** The org claim too, so a request made on another thread (a race) resolves the same organization. */
    private static RequestPostProcessor as(String role, UUID userId) {
        return jwt().jwt(token -> token.subject(userId.toString())
                        .claim("org", DemoEstateSeeder.DEMO_ORG_ID.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
