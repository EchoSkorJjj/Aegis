package com.aegis.core.actions.upgrade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aegis.core.actions.pr.FileChange;
import com.aegis.core.actions.pr.PullRequestDraft;
import com.aegis.core.actions.tracker.IssueTracker.Block;
import com.aegis.core.actions.tracker.IssueTracker.IssueDraft;
import com.aegis.core.actions.upgrade.UpgradeProposal.Edit;
import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.RepositoryUrl;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.tenant.CurrentOrg;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import com.aegis.core.support.FakeAiDrafter;
import com.aegis.core.support.FakeIssueTracker;
import com.aegis.core.support.FakeRepositoryHost;
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
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Safe-upgrade draft pull requests end to end (E8 story 4), as part of a response (story 5): drafted with the
 * risk ticket for the same vulnerability and service, and opened — under the ticket's key — only when a
 * second person approves the response. Against the in-memory repository host, issue tracker and AI drafter
 * the suite shares; every test builds its own uniquely named service, components, advisories and repository.
 */
class UpgradeProposalIntegrationTest extends AbstractInventoryIntegrationTest {

    private static final String RESPONSES = "/api/actions/risk-tickets";
    private static final String PROPOSALS = "/api/actions/upgrade-proposals";

    @Autowired private InventoryService inventory;
    @Autowired private InventoryStore inventoryStore;
    @Autowired private AdvisoryStore advisories;
    @Autowired private TestOwners owners;
    @Autowired private FakeRepositoryHost host;
    @Autowired private FakeIssueTracker tracker;
    @Autowired private FakeAiDrafter drafter;
    @Autowired private CurrentOrg currentOrg;
    @Autowired private UpgradeProposalService proposals;
    @Autowired private UpgradeProposalStore store;

    private final UUID analyst = UUID.randomUUID();
    private final UUID approver = UUID.randomUUID();

    @AfterEach
    void reset() {
        drafter.unavailable(false);
        tracker.connected(true);
    }

    @Test
    void responseAndPullRequestEvidenceIsProtectedEvenWhenAiDraftingFails() throws Exception {
        Fixture f = fixture();
        Owner owner = owners.assign(f.service().getId(), "Personal Owner " + f.tag());
        Advisory old = f.advisory();
        advisories.upsert(new Advisory(old.id(), old.sourceId(), old.externalId(), old.canonicalId(),
                "Affected library; alice@example.test; password=opaque-credential", old.publishedAt(),
                Instant.now(), old.cvss(), old.affected()));
        drafter.unavailable(true);
        String created = draft(f.advisory().id(), f.service().getId(), analyst)
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.pullRequests[0].status").value("PROPOSED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(created).contains("[REDACTED]", "1 assigned (personal details omitted)")
                .doesNotContain("alice@example.test", "opaque-credential", owner.getName());
        assertThat(host.opened(f.repository())).isEmpty();
        String proposalId = JsonPath.read(created, "$.pullRequests[0].id");
        String ticketId = JsonPath.read(created, "$.id");
        String stored = mvc.perform(get(PROPOSALS + "/{id}", proposalId).with(as("COMPLIANCE_ADMIN", approver)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(stored).contains("[REDACTED]").doesNotContain("alice@example.test", "opaque-credential", owner.getName());
        assertThat(drafter.requests()).filteredOn(sent -> sent.findingId().equals(ticketId)
                || sent.findingId().equals(proposalId)).hasSize(2).allSatisfy(sent ->
                assertThat(sent.context()).doesNotContain("alice@example.test", "opaque-credential", owner.getName()));
    }

    @Test
    void theTicketAndPullRequestKeepTheirDraftEvidenceAfterTheEstateAndFeedChange() throws Exception {
        Fixture f = fixture();
        String created = draft(f.advisory().id(), f.service().getId(), analyst)
                .andReturn().getResponse().getContentAsString();
        String ticketId = JsonPath.read(created, "$.id");
        String proposalId = JsonPath.read(created, "$.pullRequests[0].id");
        String originalBody = JsonPath.read(created, "$.pullRequests[0].body");
        List<String> originalEvidence = JsonPath.read(created, "$.evidence");
        String oldName = f.service().getName();

        inventory.updateBusinessService(f.service().getId(), oldName + " renamed", BusinessCriticality.LOW,
                ServiceExposure.ISOLATED);
        Advisory old = f.advisory();
        advisories.upsert(new Advisory(old.id(), old.sourceId(), old.externalId(), old.canonicalId(),
                "Revised advisory text.", old.publishedAt(), Instant.now(), old.cvss(), old.affected()));

        mvc.perform(get(RESPONSES + "/{id}", ticketId).with(as("COMPLIANCE_ADMIN", approver)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.evidence").value(originalEvidence));
        mvc.perform(get(PROPOSALS + "/{id}", proposalId).with(as("COMPLIANCE_ADMIN", approver)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body").value(originalBody));
        assertThat(originalBody).contains(oldName, "Source excerpt: An injection flaw in upgrade-lib.")
                .doesNotContain("Revised advisory text.");
    }

    @Test
    void approvingTheResponseFilesTheTicketThenOpensTheDraftPullRequestUnderItsKey() throws Exception {
        Fixture f = fixture();

        String created = draft(f.advisory().id(), f.service().getId(), analyst)
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.pullRequests.length()").value(1))
                .andExpect(jsonPath("$.pullRequests[0].status").value("PROPOSED"))
                .andExpect(jsonPath("$.pullRequests[0].edits[0].fromVersion").value("1.0.0"))
                // 1.2.0 fixes the remediated CVE but not the later one; 1.3.0 is the first version that fixes both.
                .andExpect(jsonPath("$.pullRequests[0].edits[0].toVersion").value("1.3.0"))
                .andExpect(jsonPath("$.pullRequests[0].edits[0].manifestPath").value("pom.xml"))
                .andExpect(jsonPath("$.pullRequests[0].vulnerabilityId").value(f.cve()))
                .andExpect(jsonPath("$.pullRequests[0].repositoryUrl").value("https://gitlab.com/" + f.repository()))
                .andExpect(jsonPath("$.pullRequests[0].headBranch").value(startsWith("aegis/upgrade-upgrade-lib-")))
                .andExpect(jsonPath("$.pullRequests[0].matchConfidence").value("CONFIRMED"))
                .andExpect(jsonPath("$.pullRequests[0].createdBy").value(analyst.toString()))
                .andExpect(jsonPath("$.pullRequests[0].title")
                        .value("Upgrade " + f.component().getName() + " from 1.0.0 to 1.3.0 (" + f.cve() + ")"))
                .andExpect(jsonPath("$.pullRequests[0].body")
                        .value(containsString("| Checked against | " + f.cve() + ", " + f.laterCve() + " |")))
                .andExpect(jsonPath("$.pullRequests[0].body").value(containsString(FakeAiDrafter.CONTENT)))
                .andExpect(jsonPath("$.pullRequests[0].body").value(containsString("### Evidence at drafting")))
                .andExpect(jsonPath("$.pullRequests[0].body").value(containsString("Source: ")))
                .andExpect(jsonPath("$.pullRequests[0].body").value(containsString("Score calculation: severity ")))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        String pullRequestId = JsonPath.read(created, "$.pullRequests[0].id");
        assertThat(JsonPath.<String>read(created, "$.pullRequests[0].riskTicketId")).isEqualTo(id);
        assertThat(host.opened(f.repository())).as("nothing is opened before a person approves").isEmpty();
        assertThat(filedFor(f.cve())).as("nothing is filed either").isEmpty();
        assertThat(drafter.requests()).anySatisfy(request -> {
            assertThat(request.findingId()).isEqualTo(pullRequestId);
            assertThat(request.draftType()).isEqualTo("upgrade");
            assertThat(request.context()).contains("An injection flaw in upgrade-lib.");
        });

        String approved = mvc.perform(post(RESPONSES + "/{id}/approve", id).with(as("ANALYST", approver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILED"))
                .andExpect(jsonPath("$.decidedBy").value(approver.toString()))
                .andExpect(jsonPath("$.pullRequests[0].status").value("OPENED"))
                .andExpect(jsonPath("$.pullRequests[0].decidedBy").value(approver.toString()))
                .andExpect(jsonPath("$.pullRequests[0].pullRequestUrl")
                        .value(startsWith("https://gitlab.com/" + f.repository() + "/pull/")))
                .andReturn().getResponse().getContentAsString();
        String key = JsonPath.read(approved, "$.issueKey");
        assertThat(JsonPath.<String>read(approved, "$.pullRequests[0].title")).startsWith(key + ": Upgrade ");

        PullRequestDraft opened = host.opened(f.repository()).getFirst();
        assertThat(opened.title()).startsWith(key + ": Upgrade " + f.component().getName());
        assertThat(opened.body()).contains("Tracks risk ticket **" + key + "**");
        assertThat(opened.baseBranch()).as("the service's default branch").isNull();
        assertThat(opened.changes()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("pom.xml");
            assertThat(change.content()).isEqualTo(pom(f.component().getName(), "1.3.0"));
            assertThat(change.expectedRevision()).as("pinned to the revision it was computed from").isNotNull();
        });
        assertThat(filedFor(f.cve())).singleElement().satisfies(issue -> assertThat(issue.description())
                .extracting(Block::text).contains("Upgrade pull request"));
    }

    @Test
    void analystsAndTheServicesOwnersDraftAndDecideWhileComplianceOnlyAudits() throws Exception {
        Fixture f = fixture();
        Owner owner = owners.assign(f.service().getId(), "Upgrade owner " + f.tag());
        Owner coOwner = owners.assign(f.service().getId(), "Upgrade co-owner " + f.tag());
        UUID stranger = UUID.randomUUID();
        UUID compliance = UUID.randomUUID();

        mvc.perform(post(RESPONSES).contentType(MediaType.APPLICATION_JSON).content(request(f)))
                .andExpect(status().isUnauthorized());
        for (String role : new String[]{"SERVICE_OWNER", "COMPLIANCE_ADMIN", "USER"}) {
            mvc.perform(post(RESPONSES).with(as(role, stranger)).contentType(MediaType.APPLICATION_JSON)
                    .content(request(f))).andExpect(status().isForbidden());
        }

        String created = draft(f.advisory().id(), f.service().getId(), owner.getUserId())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        String pullRequestId = JsonPath.read(created, "$.pullRequests[0].id");
        mvc.perform(get(RESPONSES + "/{id}", id).with(as("COMPLIANCE_ADMIN", compliance)))
                .andExpect(status().isOk()); // any domain role may read, so compliance can audit it
        mvc.perform(get(PROPOSALS + "/{id}", pullRequestId).with(as("COMPLIANCE_ADMIN", compliance)))
                .andExpect(status().isOk());
        for (RequestPostProcessor outsider : List.of(as("SERVICE_OWNER", stranger), as("COMPLIANCE_ADMIN", compliance))) {
            mvc.perform(post(RESPONSES + "/{id}/approve", id).with(outsider)).andExpect(status().isForbidden());
            mvc.perform(post(PROPOSALS + "/{id}/dismiss", pullRequestId).with(outsider)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"not mine\"}"))
                    .andExpect(status().isForbidden());
        }
        assertThat(host.opened(f.repository())).isEmpty();

        // Another of the service's owners approves what one of them drafted.
        mvc.perform(post(RESPONSES + "/{id}/approve", id).with(as("SERVICE_OWNER", coOwner.getUserId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILED"))
                .andExpect(jsonPath("$.pullRequests[0].status").value("OPENED"))
                .andExpect(jsonPath("$.pullRequests[0].createdBy").value(owner.getUserId().toString()))
                .andExpect(jsonPath("$.pullRequests[0].decidedBy").value(coOwner.getUserId().toString()));
    }

    @Test
    void whoeverDraftedTheResponseNeverApprovesItsPullRequestAndAFailedOpeningIsRetriedOnItsOwn() throws Exception {
        Fixture f = fixture();
        String created = draft(f.advisory().id(), f.service().getId(), analyst)
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        String pullRequestId = JsonPath.read(created, "$.pullRequests[0].id");

        mvc.perform(post(RESPONSES + "/{id}/approve", id).with(as("ANALYST", analyst)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("someone else has to approve it")));
        assertThat(filedFor(f.cve())).isEmpty();
        // Nor around the response, on the pull request itself.
        mvc.perform(post(PROPOSALS + "/{id}/approve", pullRequestId).with(as("ANALYST", approver)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("decide it on the response")));

        host.failNextOpen(new ApiExceptions.UpstreamUnavailable("GitHub is unavailable"));
        mvc.perform(post(RESPONSES + "/{id}/approve", id).with(as("ANALYST", approver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILED"))
                .andExpect(jsonPath("$.pullRequests[0].status").value("FAILED"))
                .andExpect(jsonPath("$.pullRequests[0].failureMessage").value("GitHub is unavailable"));

        // Retrying the opening is approving it too, so it is never the drafter's either.
        String retry = RESPONSES + "/{id}/pull-requests/{pullRequest}/approve";
        mvc.perform(post(retry, id, pullRequestId).with(as("ANALYST", analyst))).andExpect(status().isForbidden());
        mvc.perform(post(retry, id, pullRequestId).with(as("ANALYST", approver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pullRequests[0].status").value("OPENED"))
                .andExpect(jsonPath("$.pullRequests[0].failureMessage").value(nullValue()));
        assertThat(host.opened(f.repository())).hasSize(1);
        assertThat(filedFor(f.cve())).as("the ticket was filed once").hasSize(1);
    }

    @Test
    void theDrafterMayLeaveThePullRequestOutAndFileTheTicketThemselves() throws Exception {
        Fixture f = fixture();
        String created = draft(f.advisory().id(), f.service().getId(), analyst)
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        mvc.perform(post(PROPOSALS + "/{id}/dismiss", JsonPath.<String>read(created, "$.pullRequests[0].id"))
                        .with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"We upgrade by hand with the next release\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));
        mvc.perform(post(RESPONSES + "/{id}/approve", id).with(as("ANALYST", analyst)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILED"))
                .andExpect(jsonPath("$.pullRequests[0].status").value("DISMISSED"));

        assertThat(host.opened(f.repository())).isEmpty();
        assertThat(filedFor(f.cve())).singleElement().satisfies(issue -> assertThat(issue.description())
                .extracting(Block::text).doesNotContain("Upgrade pull request"));
    }

    @Test
    void anApprovalIsOneShot() throws Exception {
        Fixture f = fixture();
        String id = id(draft(f.advisory().id(), f.service().getId(), analyst));

        mvc.perform(post(RESPONSES + "/{id}/approve", id).with(as("ANALYST", approver))).andExpect(status().isOk());
        mvc.perform(post(RESPONSES + "/{id}/approve", id).with(as("ANALYST", approver)))
                .andExpect(status().isConflict());
        mvc.perform(post(RESPONSES + "/{id}/dismiss", id).with(as("ANALYST", approver))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"too late\"}"))
                .andExpect(status().isConflict());
        // The filed one is current, so the same response cannot be drafted twice.
        mvc.perform(post(RESPONSES).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                        .content(request(f)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("already filed")));
        assertThat(host.opened(f.repository())).hasSize(1);
    }

    @Test
    void dismissingTheResponseDismissesItsPullRequestAndFreesTheSlot() throws Exception {
        Fixture f = fixture();
        String created = draft(f.advisory().id(), f.service().getId(), analyst)
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        mvc.perform(post(RESPONSES + "/{id}/dismiss", id).with(as("ANALYST", approver))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(RESPONSES + "/{id}/dismiss", id).with(as("ANALYST", approver))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" Upgrading with the next release \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"))
                .andExpect(jsonPath("$.dismissReason").value("Upgrading with the next release"))
                .andExpect(jsonPath("$.pullRequests[0].status").value("DISMISSED"))
                .andExpect(jsonPath("$.pullRequests[0].dismissReason").value("Upgrading with the next release"))
                .andExpect(jsonPath("$.pullRequests[0].decidedBy").value(approver.toString()));
        mvc.perform(post(RESPONSES + "/{id}/approve", id).with(as("ANALYST", approver)))
                .andExpect(status().isConflict());

        draft(f.advisory().id(), f.service().getId(), analyst);
        mvc.perform(get(PROPOSALS).param("serviceId", f.service().getId().toString()).param("status", "DISMISSED")
                        .with(as("SERVICE_OWNER", UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(JsonPath.<String>read(created, "$.pullRequests[0].id")))
                .andExpect(jsonPath("$.content[0].serviceName").value(f.service().getName()));
        assertThat(host.opened(f.repository())).isEmpty();
    }

    @Test
    void aManifestThatDoesNotDeclareTheDependencyFailsToOpenWithTheReason() throws Exception {
        Fixture f = fixture();
        host.put(f.repository(), "pom.xml", "<project><dependencies/></project>");
        String id = id(draft(f.advisory().id(), f.service().getId(), analyst));

        mvc.perform(post(RESPONSES + "/{id}/approve", id).with(as("ANALYST", approver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILED"))
                .andExpect(jsonPath("$.pullRequests[0].status").value("FAILED"))
                .andExpect(jsonPath("$.pullRequests[0].failureMessage").value(containsString("is not declared in pom.xml")));
        assertThat(host.opened(f.repository())).isEmpty();
    }

    @Test
    void anAiOutageNeverBlocksTheFix() throws Exception {
        Fixture f = fixture();
        drafter.unavailable(true);

        draft(f.advisory().id(), f.service().getId(), analyst)
                .andExpect(jsonPath("$.aiDrafted").value(false))
                .andExpect(jsonPath("$.pullRequests[0].edits[0].toVersion").value("1.3.0"))
                .andExpect(jsonPath("$.pullRequests[0].body")
                        .value(containsString("_No AI rationale: the AI service could not draft one._")));
    }

    @Test
    void whatCannotBeAutomatedIsRecordedOnTheTicketAndDraftedOnceItCanBe() throws Exception {
        Fixture f = fixture();

        BusinessService unlinked = inventory.createBusinessService(
                "Unlinked " + f.tag(), BusinessCriticality.LOW, ServiceExposure.ISOLATED);
        inventory.addServiceComponent(unlinked.getId(), f.component().getId());
        String id = id(draft(f.advisory().id(), unlinked.getId(), analyst)
                .andExpect(jsonPath("$.pullRequests").isEmpty())
                .andExpect(jsonPath("$.evidence").value(hasItem("Not automated: Link the service's repository so Aegis "
                        + "can draft the upgrade pull request for " + f.component().getName()))));
        mvc.perform(post(RESPONSES + "/{id}/pull-requests", id).with(as("ANALYST", analyst)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(startsWith("Nothing Aegis can automate: Link the service's repository")));

        String repository = "acme/unlinked-" + f.tag();
        inventory.linkServiceRepository(unlinked.getId(), hosted(repository), null);
        host.put(repository, "pom.xml", pom(f.component().getName(), "1.0.0"));
        mvc.perform(post(RESPONSES + "/{id}/pull-requests", id).with(as("ANALYST", analyst)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pullRequests[0].status").value("PROPOSED"))
                .andExpect(jsonPath("$.pullRequests[0].riskTicketId").value(id));
        mvc.perform(post(RESPONSES + "/{id}/pull-requests", id).with(as("ANALYST", analyst)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("already has an upgrade pull request")));

        SoftwareComponent framework = inventory.createSoftwareComponent("maven", "com.acme:framework-" + f.tag(), "3.0.0");
        inventory.addComponentDependency(framework.getId(), f.component().getId());
        BusinessService indirect = inventory.createBusinessService(
                "Indirect " + f.tag(), BusinessCriticality.HIGH, ServiceExposure.INTERNAL);
        inventory.linkServiceRepository(indirect.getId(), hosted("acme/indirect-" + f.tag()), null);
        inventory.addServiceComponent(indirect.getId(), framework.getId());
        draft(f.advisory().id(), indirect.getId(), analyst)
                .andExpect(jsonPath("$.pullRequests").isEmpty())
                .andExpect(jsonPath("$.evidence").value(hasItem("Not automated: " + f.component().getName()
                        + " 1.0.0 comes in through com.acme:framework-" + f.tag() + "; upgrade com.acme:framework-"
                        + f.tag() + ", or pin " + f.component().getName() + " by hand")));

        mvc.perform(post(RESPONSES).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                        .content(request(UUID.randomUUID(), f.service().getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void onePullRequestUpgradesEveryManifestThatDeclaresAnAffectedVersion() throws Exception {
        String tag = tag();
        String name = "grp-" + tag;
        String repository = "acme/grouped-" + tag;
        BusinessService service = inventory.createBusinessService(
                "Grouped " + tag, BusinessCriticality.CRITICAL, ServiceExposure.INTERNET_FACING);
        inventory.linkServiceRepository(service.getId(), hosted(repository), null);
        // One version declared in two projects' manifests, another version in a third.
        SoftwareComponent shared = inventory.createSoftwareComponent("npm", name, "1.0.0");
        SoftwareComponent older = inventory.createSoftwareComponent("npm", name, "1.1.0");
        inventoryStore.linkRepositoryComponent(service.getId(), shared.getId(),
                List.of("client/package.json", "server/package.json"));
        inventoryStore.linkRepositoryComponent(service.getId(), older.getId(), List.of("worker/package.json"));
        Advisory advisory = advisories.upsert(new Advisory(null, "osv", "GHSA-" + tag, "CVE-2098-" + tag,
                "A flaw in grp.", Instant.now(), Instant.now(), new Advisory.Cvss("3.1", null, new BigDecimal("8.8"), "HIGH"),
                List.of(AffectedProduct.ofPackage("npm", name, null, "1.0.0", null, null, "1.2.0"))));
        host.put(repository, "client/package.json", packageJson(name, "^1.0.0"));
        host.put(repository, "server/package.json", packageJson(name, "1.0.0"));
        host.put(repository, "worker/package.json", packageJson(name, "~1.1.0"));

        String created = draft(advisory.id(), service.getId(), analyst)
                .andExpect(jsonPath("$.pullRequests.length()").value(1))
                .andExpect(jsonPath("$.pullRequests[0].edits.length()").value(3))
                .andExpect(jsonPath("$.pullRequests[0].title").value("Upgrade " + name + " to 1.2.0 in 3 manifests (CVE-2098-"
                        + tag + ")"))
                // Aegis edits package.json only: the lockfile beside each one is left to the reviewer, by name.
                .andExpect(jsonPath("$.pullRequests[0].body").value(containsString("### Before merging\n\n- [ ] Refresh "
                        + "the lockfiles in `client/`, `server/` and `worker/` and commit them to this branch")))
                .andReturn().getResponse().getContentAsString();

        mvc.perform(post(RESPONSES + "/{id}/approve", JsonPath.<String>read(created, "$.id")).with(as("ANALYST", approver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pullRequests[0].status").value("OPENED"));
        assertThat(host.opened(repository)).singleElement().satisfies(opened -> assertThat(opened.changes())
                .extracting(FileChange::path, FileChange::content)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("client/package.json", packageJson(name, "^1.2.0")),
                        org.assertj.core.groups.Tuple.tuple("server/package.json", packageJson(name, "1.2.0")),
                        org.assertj.core.groups.Tuple.tuple("worker/package.json", packageJson(name, "~1.2.0"))));
    }

    @Test
    void aDependencyAnotherPullRequestUpgradesIsLeftToIt() throws Exception {
        Fixture f = fixture();
        draft(f.advisory().id(), f.service().getId(), analyst).andExpect(jsonPath("$.pullRequests.length()").value(1));

        // The later CVE affects the same library: its response is the ticket, and says who upgrades it.
        UUID later = advisories.findByExternalId("osv", "GHSA-" + f.tag()).orElseThrow().id();
        draft(later, f.service().getId(), analyst)
                .andExpect(jsonPath("$.pullRequests").isEmpty())
                .andExpect(jsonPath("$.evidence").value(hasItem("Not automated: " + f.component().getName()
                        + " in pom.xml is already being upgraded by another upgrade pull request")));
    }

    @Test
    void aProposalDraftedBeforeResponsesIsStillDecidedOnItsOwn() throws Exception {
        Fixture f = fixture();
        UpgradeProposal standalone = store.create(new UpgradeProposal(UUID.randomUUID(), f.service().getId(),
                f.advisory().id(), f.cve(), null,
                List.of(new Edit(f.component().getId(), "maven", f.component().getName(), "1.0.0", "1.3.0", "pom.xml")),
                "https://gitlab.com/" + f.repository(), null, "aegis/upgrade-standalone-" + f.tag(),
                MatchConfidence.CONFIRMED, "Upgrade it", "body", UpgradeProposalStatus.PROPOSED, analyst, null, null,
                null, null, null, null, null, 0));

        mvc.perform(get(PROPOSALS).param("serviceId", f.service().getId().toString()).param("standalone", "true")
                        .with(as("ANALYST", approver)))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(standalone.id().toString()));
        mvc.perform(post(PROPOSALS + "/{id}/approve", standalone.id()).with(as("ANALYST", analyst)))
                .andExpect(status().isForbidden());
        mvc.perform(post(PROPOSALS + "/{id}/approve", standalone.id()).with(as("ANALYST", approver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPENED"))
                .andExpect(jsonPath("$.title").value("Upgrade it"));
    }

    @Test
    void theReviewQueueKeepsAResponseUntilItsPullRequestIsDone() throws Exception {
        Fixture f = fixture();
        String created = draft(f.advisory().id(), f.service().getId(), analyst)
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        String service = f.service().getId().toString();

        pending(service, true).andExpect(jsonPath("$.content[*].id").value(hasItem(id)));
        host.failNextOpen(new ApiExceptions.UpstreamUnavailable("GitHub is unavailable"));
        mvc.perform(post(RESPONSES + "/{id}/approve", id).with(as("ANALYST", approver))).andExpect(status().isOk());
        // Filed, but its pull request failed: still someone's to act on.
        pending(service, true).andExpect(jsonPath("$.content[*].id").value(hasItem(id)));
        pending(service, false).andExpect(jsonPath("$.content[*].id").value(not(hasItem(id))));

        mvc.perform(post(RESPONSES + "/{id}/pull-requests/{pullRequest}/approve", id,
                JsonPath.<String>read(created, "$.pullRequests[0].id")).with(as("ANALYST", approver)))
                .andExpect(status().isOk());
        pending(service, true).andExpect(jsonPath("$.content[*].id").value(not(hasItem(id))));
        pending(service, false).andExpect(jsonPath("$.content[*].id").value(hasItem(id)));
    }

    @Test
    void anotherOrganizationSeesNoneOfIt() throws Exception {
        Fixture f = fixture();
        UUID pullRequest = UUID.fromString(JsonPath.read(draft(f.advisory().id(), f.service().getId(), analyst)
                .andReturn().getResponse().getContentAsString(), "$.pullRequests[0].id"));

        Throwable[] seen = new Throwable[1];
        long[] listed = {-1};
        currentOrg.runAs(UUID.randomUUID(), () -> {
            listed[0] = proposals.list(null, null, null, false, Pageable.unpaged()).getTotalElements();
            try {
                proposals.get(pullRequest);
            } catch (Throwable failure) {
                seen[0] = failure;
            }
        });
        assertThat(seen[0]).isInstanceOf(ApiExceptions.NotFound.class);
        assertThat(listed[0]).isZero();
    }

    // ---- fixture -----------------------------------------------------------------------------------

    /**
     * A HIGH, internal service linked to its own repository, directly using one Maven component that an
     * NVD advisory (the one remediated, fixed in 1.2.0) and a later OSV advisory (fixed only in 1.3.0) affect.
     */
    private Fixture fixture() {
        String tag = tag();
        String artifact = "upgrade-lib-" + tag;
        String name = "com.acme:" + artifact;
        String repository = "acme/upgrade-" + tag;
        BusinessService service = inventory.createBusinessService(
                "Upgrade " + tag, BusinessCriticality.HIGH, ServiceExposure.INTERNAL);
        inventory.linkServiceRepository(service.getId(), hosted(repository), null);
        SoftwareComponent component = inventory.createSoftwareComponent("maven", name, "1.0.0");
        inventory.addServiceComponent(service.getId(), component.getId());
        Advisory advisory = advisories.upsert(new Advisory(
                null, "nvd", "CVE-2099-" + tag + "1", "An injection flaw in upgrade-lib.", Instant.now(), Instant.now(),
                new Advisory.Cvss("3.1", null, new BigDecimal("9.8"), "CRITICAL"),
                List.of(new AffectedProduct("cpe:2.3:a:acme:" + artifact + ":*:*:*:*:*:*:*:*", "a", "acme", artifact,
                        null, "1.0.0", null, null, "1.2.0"))));
        advisories.upsert(new Advisory(
                null, "osv", "GHSA-" + tag, "CVE-2099-" + tag + "2", "A later flaw.", Instant.now(), Instant.now(), null,
                List.of(AffectedProduct.ofPackage("Maven", name, null, "1.0.0", null, null, "1.3.0"))));
        host.put(repository, "pom.xml", pom(name, "1.0.0"));
        return new Fixture(tag, service, component, advisory, repository);
    }

    private record Fixture(String tag, BusinessService service, SoftwareComponent component, Advisory advisory,
                           String repository) {
        String cve() {
            return advisory.canonicalId();
        }

        String laterCve() {
            return "CVE-2099-" + tag + "2";
        }
    }

    private static String tag() {
        return Long.toHexString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
    }

    private static String pom(String name, String version) {
        String[] coordinates = name.split(":");
        return """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>%s</groupId>
                      <artifactId>%s</artifactId>
                      <version>%s</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(coordinates[0], coordinates[1], version);
    }

    private static String packageJson(String name, String spec) {
        return """
                {
                  "name": "app",
                  "dependencies": {
                    "%s": "%s"
                  }
                }
                """.formatted(name, spec);
    }

    /** The fake repository host serves gitlab.com (see FakeRepositoryHost). */
    private static RepositoryUrl hosted(String repository) {
        return RepositoryUrl.parse("https://gitlab.com/" + repository, List.of("gitlab.com"));
    }

    private ResultActions draft(UUID changeSignalId, UUID serviceId, UUID drafterId) throws Exception {
        return mvc.perform(post(RESPONSES).with(as("ANALYST", drafterId)).contentType(MediaType.APPLICATION_JSON)
                        .content(request(changeSignalId, serviceId)))
                .andExpect(status().isCreated());
    }

    private ResultActions pending(String serviceId, boolean pending) throws Exception {
        return mvc.perform(get(RESPONSES).param("serviceId", serviceId).param("pending", String.valueOf(pending))
                .with(as("ANALYST", approver))).andExpect(status().isOk());
    }

    private List<IssueDraft> filedFor(String cve) {
        return tracker.filed().stream().filter(issue -> issue.title().contains(cve)).toList();
    }

    private static String request(Fixture f) {
        return request(f.advisory().id(), f.service().getId());
    }

    private static String request(UUID changeSignalId, UUID serviceId) {
        return "{\"changeSignalId\":\"%s\",\"serviceId\":\"%s\"}".formatted(changeSignalId, serviceId);
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    /** A caller with one role whose token subject is {@code userId} (what decisions are recorded against). */
    private static RequestPostProcessor as(String role, UUID userId) {
        return jwt().jwt(token -> token.subject(userId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
