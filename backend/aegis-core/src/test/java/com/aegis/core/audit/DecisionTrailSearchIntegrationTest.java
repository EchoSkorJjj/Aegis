package com.aegis.core.audit;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aegis.core.audit.DecisionRecord.Action;
import com.aegis.core.audit.DecisionRecord.Subject;
import com.aegis.core.inventory.demo.DemoEstateSeeder;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Searching the decision trail (E10 story 2): by the person who took each step, the step, what it was taken on and
 * a period, combined with the service, oldest or newest first. How the records come to be written is
 * {@link DecisionTrailIntegrationTest}'s; here they are appended directly, each under a service id of the test's own
 * (no foreign keys), so a search scoped to it sees only that test's records whatever else the shared database holds.
 */
class DecisionTrailSearchIntegrationTest extends AbstractInventoryIntegrationTest {

    private static final String TRAIL = "/api/audit/decisions";

    @Autowired private DecisionTrail trail;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID auditor = UUID.randomUUID();

    @Test
    void aPersonsDecisionsInAServiceAreFoundAmongEveryoneElses() throws Exception {
        UUID service = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID ticket = UUID.randomUUID();
        UUID memo = UUID.randomUUID();
        record(service, ticket, Subject.RISK_TICKET, Action.DRAFTED, alice, null);
        record(service, ticket, Subject.RISK_TICKET, Action.EDITED, bob, null);
        record(service, ticket, Subject.RISK_TICKET, Action.APPROVED, bob, null);
        // A memo covering this service and another, approved by the same person.
        recordMemo(List.of(service, other), memo, Action.APPROVED, bob);
        // The same person's decision in another service only: not in this service's search.
        record(other, UUID.randomUUID(), Subject.RISK_TICKET, Action.DISMISSED, bob, "Accepted risk");

        search(get(TRAIL).param("serviceId", service.toString()).param("actorId", bob.toString()))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].action").value(contains("EDITED", "APPROVED", "APPROVED")))
                .andExpect(jsonPath("$.content[*].subject")
                        .value(contains("RISK_TICKET", "RISK_TICKET", "REGULATOR_MEMO")))
                .andExpect(jsonPath("$.content[2].responseId").value(memo.toString()));
        search(get(TRAIL).param("serviceId", service.toString()).param("actorId", alice.toString()))
                .andExpect(jsonPath("$.content[*].action").value(contains("DRAFTED")));
        // Everything the person did, in every service.
        search(get(TRAIL).param("actorId", bob.toString()))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[3].reason").value("Accepted risk"));
        // Nobody else's.
        search(get(TRAIL).param("actorId", UUID.randomUUID().toString()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void decisionsAreFoundByStepAndByWhatTheyWereTakenOn() throws Exception {
        UUID service = UUID.randomUUID();
        UUID ticket = UUID.randomUUID();
        record(service, ticket, Subject.RISK_TICKET, Action.DRAFTED, alice, null);
        record(service, ticket, Subject.UPGRADE_PULL_REQUEST, Action.DRAFTED, alice, null);
        record(service, ticket, Subject.RISK_TICKET, Action.OVERRIDDEN, bob, "Behind the WAF");
        record(service, ticket, Subject.RISK_TICKET, Action.APPROVED, bob, null);
        record(service, ticket, Subject.UPGRADE_PULL_REQUEST, Action.DISMISSED, alice, "We upgrade by hand");
        recordMemo(List.of(service), UUID.randomUUID(), Action.DISMISSED, bob);

        // Any of several steps.
        search(get(TRAIL).param("serviceId", service.toString()).param("action", "OVERRIDDEN,DISMISSED"))
                .andExpect(jsonPath("$.content[*].action").value(contains("OVERRIDDEN", "DISMISSED", "DISMISSED")))
                .andExpect(jsonPath("$.content[*].reason").value(contains("Behind the WAF", "We upgrade by hand",
                        "Not significant")));
        // The parameter repeated means the same.
        search(get(TRAIL).param("serviceId", service.toString()).param("action", "APPROVED")
                        .param("action", "OVERRIDDEN"))
                .andExpect(jsonPath("$.content[*].action").value(contains("OVERRIDDEN", "APPROVED")));
        // What it was taken on, with the step.
        search(get(TRAIL).param("serviceId", service.toString()).param("subject", "UPGRADE_PULL_REQUEST")
                        .param("action", "DISMISSED"))
                .andExpect(jsonPath("$.content[*].reason").value(contains("We upgrade by hand")));
        search(get(TRAIL).param("serviceId", service.toString()).param("subject", "REGULATOR_MEMO,RISK_TICKET"))
                .andExpect(jsonPath("$.totalElements").value(4));
    }

    @Test
    void aPeriodIncludesItsStartAndExcludesItsEnd() throws Exception {
        UUID service = UUID.randomUUID();
        UUID ticket = UUID.randomUUID();
        record(service, ticket, Subject.RISK_TICKET, Action.DRAFTED, alice, null);
        apart();
        record(service, ticket, Subject.RISK_TICKET, Action.APPROVED, bob, null);
        apart();
        record(service, ticket, Subject.RISK_TICKET, Action.FILED, bob, null);

        String all = search(get(TRAIL).param("serviceId", service.toString()))
                .andReturn().getResponse().getContentAsString();
        String drafted = JsonPath.read(all, "$.content[0].occurredAt");
        String approved = JsonPath.read(all, "$.content[1].occurredAt");
        String filed = JsonPath.read(all, "$.content[2].occurredAt");

        search(get(TRAIL).param("serviceId", service.toString()).param("from", approved))
                .andExpect(jsonPath("$.content[*].action").value(contains("APPROVED", "FILED")));
        search(get(TRAIL).param("serviceId", service.toString()).param("to", approved))
                .andExpect(jsonPath("$.content[*].action").value(contains("DRAFTED")));
        search(get(TRAIL).param("serviceId", service.toString()).param("from", approved).param("to", filed))
                .andExpect(jsonPath("$.content[*].action").value(contains("APPROVED")));
        // With the person too: what they did in the period.
        search(get(TRAIL).param("serviceId", service.toString()).param("from", drafted).param("to", filed)
                        .param("actorId", bob.toString()))
                .andExpect(jsonPath("$.content[*].action").value(contains("APPROVED")));
    }

    @Test
    void theTrailIsReadNewestFirstOnRequest() throws Exception {
        UUID service = UUID.randomUUID();
        UUID ticket = UUID.randomUUID();
        record(service, ticket, Subject.RISK_TICKET, Action.DRAFTED, alice, null);
        record(service, ticket, Subject.RISK_TICKET, Action.EDITED, bob, null);
        record(service, ticket, Subject.RISK_TICKET, Action.APPROVED, bob, null);

        search(get(TRAIL).param("serviceId", service.toString()).param("sort", "sequence,desc"))
                .andExpect(jsonPath("$.content[*].action").value(contains("APPROVED", "EDITED", "DRAFTED")));
        // occurredAt is the same order, by its other name; paging follows it.
        search(get(TRAIL).param("serviceId", service.toString()).param("sort", "occurredAt,desc")
                        .param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].action").value(contains("DRAFTED")));
    }

    @Test
    void aSearchThatCannotBeAnsweredIsRefused() throws Exception {
        String now = "2026-10-12T00:00:00Z";
        refused(get(TRAIL).param("from", now).param("to", now))
                .andExpect(jsonPath("$.message").value(containsString("before its end")));
        refused(get(TRAIL).param("from", "2026-10-13T00:00:00Z").param("to", now));
        refused(get(TRAIL).param("sort", "serviceName,asc"))
                .andExpect(jsonPath("$.message").value(containsString("sorts only by sequence")));
        refused(get(TRAIL).param("action", "DELETED"));
        refused(get(TRAIL).param("from", "yesterday"));
        refused(get(TRAIL).param("actorId", "bob"));
    }

    @Test
    void onlyGovernanceSearchesTheTrail() throws Exception {
        mvc.perform(get(TRAIL).param("actorId", alice.toString()).with(as("SERVICE_OWNER", alice)))
                .andExpect(status().isForbidden());
        mvc.perform(get(TRAIL).param("actorId", alice.toString()).with(as("ANALYST", bob)))
                .andExpect(status().isOk());
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** Append one step on a ticket or pull request in {@code service}, as {@link DecisionTrail#recorded} does. */
    private void record(UUID service, UUID response, Subject subject, Action action, UUID actor, String reason) {
        trail.recorded(() -> response, saved -> new DecisionRecord.Entry(subject, UUID.randomUUID(), saved, service,
                "Search " + service, List.of(service), UUID.randomUUID(), "CVE-2097-0001 in search-lib", action,
                actor, reason, 72.5, ImpactTier.HIGH, Map.of("status", action.name())));
    }

    /** Append one step on a regulator memo covering {@code services}. */
    private void recordMemo(List<UUID> services, UUID memo, Action action, UUID actor) {
        trail.recorded(() -> memo, saved -> new DecisionRecord.Entry(Subject.REGULATOR_MEMO, saved, saved, null, null,
                services, UUID.randomUUID(), "CVE-2097-0002 in search-lib", action, actor,
                action == Action.DISMISSED ? "Not significant" : null, 90.0, ImpactTier.CRITICAL,
                Map.of("status", action.name())));
    }

    /** Records written apart in time, so a period can fall between them (the database stamps them). */
    private static void apart() throws InterruptedException {
        Thread.sleep(20);
    }

    private ResultActions search(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(as("COMPLIANCE_ADMIN", auditor))).andExpect(status().isOk());
    }

    private ResultActions refused(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(as("COMPLIANCE_ADMIN", auditor))).andExpect(status().isBadRequest());
    }

    private static RequestPostProcessor as(String role, UUID userId) {
        return jwt().jwt(token -> token.subject(userId.toString())
                        .claim("org", DemoEstateSeeder.DEMO_ORG_ID.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
