package com.aegis.core.actions.upgrade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aegis.core.actions.pr.PullRequestDraft;
import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.RepositoryUrl;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.tenant.CurrentOrg;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import com.aegis.core.support.FakeAiDrafter;
import com.aegis.core.support.FakeRepositoryHost;
import com.aegis.core.support.TestOwners;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Safe-upgrade draft pull requests end to end (E8 story 4, with E9's decision step), against the
 * in-memory repository host and AI drafter the suite shares. Every test builds its own uniquely named
 * service, component, advisories and repository.
 */
class UpgradeProposalIntegrationTest extends AbstractInventoryIntegrationTest {

    private static final String PATH = "/api/actions/upgrade-proposals";

    @Autowired
    private InventoryService inventory;

    @Autowired
    private AdvisoryStore advisories;

    @Autowired
    private TestOwners owners;

    @Autowired
    private FakeRepositoryHost host;

    @Autowired
    private FakeAiDrafter drafter;

    @Autowired
    private CurrentOrg currentOrg;

    @Autowired
    private UpgradeProposalService proposals;

    private final UUID analyst = UUID.randomUUID();
    private final UUID approver = UUID.randomUUID();

    @Test
    void aPersonApprovesTheSafeUpgradeAndOnlyThenIsADraftPullRequestOpened() throws Exception {
        Fixture f = fixture();

        String id = id(mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                        .content(proposal(f)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.serviceName").value(f.service().getName()))
                .andExpect(jsonPath("$.fromVersion").value("1.0.0"))
                // 1.2.0 fixes the remediated CVE but not the later one; 1.3.0 is the first version that fixes both.
                .andExpect(jsonPath("$.toVersion").value("1.3.0"))
                .andExpect(jsonPath("$.vulnerabilityId").value(f.cve()))
                .andExpect(jsonPath("$.manifestPath").value("pom.xml"))
                .andExpect(jsonPath("$.repositoryUrl").value("https://gitlab.com/" + f.repository()))
                .andExpect(jsonPath("$.headBranch").value(startsWith("aegis/upgrade-upgrade-lib-")))
                .andExpect(jsonPath("$.matchConfidence").value("CONFIRMED"))
                .andExpect(jsonPath("$.createdBy").value(analyst.toString()))
                .andExpect(jsonPath("$.title").value("Upgrade " + f.component().getName() + " from 1.0.0 to 1.3.0 (" + f.cve() + ")"))
                .andExpect(jsonPath("$.body").value(containsString("| Checked against | " + f.cve() + ", " + f.laterCve() + " |")))
                .andExpect(jsonPath("$.body").value(containsString(FakeAiDrafter.CONTENT))));
        assertThat(host.opened(f.repository())).as("nothing is opened before a person approves").isEmpty();
        assertThat(drafter.requests()).anySatisfy(request -> {
            assertThat(request.findingId()).isEqualTo(id);
            assertThat(request.draftType()).isEqualTo("upgrade");
            assertThat(request.context()).contains("An injection flaw in upgrade-lib.");
        });

        mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", approver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPENED"))
                .andExpect(jsonPath("$.decidedBy").value(approver.toString()))
                .andExpect(jsonPath("$.pullRequestUrl").value(startsWith("https://gitlab.com/" + f.repository() + "/pull/")));

        PullRequestDraft draft = host.opened(f.repository()).getFirst();
        assertThat(draft.headBranch()).startsWith("aegis/upgrade-");
        assertThat(draft.baseBranch()).as("the service's default branch").isNull();
        assertThat(draft.changes()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("pom.xml");
            assertThat(change.content()).isEqualTo(pom(f.component().getName(), "1.3.0"));
            assertThat(change.expectedRevision()).as("pinned to the revision it was computed from").isNotNull();
        });
    }

    @Test
    void analystsAndTheServicesOwnersDraftAndDecideWhileComplianceOnlyAudits() throws Exception {
        Fixture f = fixture();
        Owner owner = owners.assign(f.service().getId(), "Upgrade owner " + f.tag());
        Owner coOwner = owners.assign(f.service().getId(), "Upgrade co-owner " + f.tag());
        UUID stranger = UUID.randomUUID();
        UUID compliance = UUID.randomUUID();

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(proposal(f)))
                .andExpect(status().isUnauthorized());
        for (String role : new String[]{"SERVICE_OWNER", "COMPLIANCE_ADMIN", "USER"}) {
            mvc.perform(post(PATH).with(as(role, stranger)).contentType(MediaType.APPLICATION_JSON).content(proposal(f)))
                    .andExpect(status().isForbidden());
        }

        String id = id(mvc.perform(post(PATH).with(as("SERVICE_OWNER", owner.getUserId()))
                        .contentType(MediaType.APPLICATION_JSON).content(proposal(f)))
                .andExpect(status().isCreated()));
        mvc.perform(get(PATH + "/{id}", id).with(as("COMPLIANCE_ADMIN", compliance)))
                .andExpect(status().isOk()); // any domain role may read, so compliance can audit it
        for (RequestPostProcessor outsider : List.of(as("SERVICE_OWNER", stranger), as("COMPLIANCE_ADMIN", compliance))) {
            mvc.perform(post(PATH + "/{id}/approve", id).with(outsider))
                    .andExpect(status().isForbidden());
            mvc.perform(post(PATH + "/{id}/dismiss", id).with(outsider)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"not mine\"}"))
                    .andExpect(status().isForbidden());
        }
        assertThat(host.opened(f.repository())).isEmpty();

        // Another of the service's owners approves what one of them drafted.
        mvc.perform(post(PATH + "/{id}/approve", id).with(as("SERVICE_OWNER", coOwner.getUserId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPENED"))
                .andExpect(jsonPath("$.createdBy").value(owner.getUserId().toString()))
                .andExpect(jsonPath("$.decidedBy").value(coOwner.getUserId().toString()));
    }

    @Test
    void whoeverDraftedAProposalNeverApprovesIt() throws Exception {
        Fixture f = fixture();
        String id = proposeAsAnalyst(f);

        mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", analyst)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("someone else has to approve it")));
        assertThat(host.opened(f.repository())).isEmpty();

        // Retrying a failed opening is approving too, so it is never the drafter's either.
        host.failNextOpen(new ApiExceptions.UpstreamUnavailable("GitHub is unavailable"));
        mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", approver)))
                .andExpect(status().isBadGateway());
        mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", analyst)))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", approver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPENED"))
                .andExpect(jsonPath("$.decidedBy").value(approver.toString()));
    }

    @Test
    void theDrafterMayWithdrawTheirOwnDraft() throws Exception {
        Fixture f = fixture();
        String id = proposeAsAnalyst(f);

        mvc.perform(post(PATH + "/{id}/dismiss", id).with(as("ANALYST", analyst))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Drafted for the wrong manifest\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"))
                .andExpect(jsonPath("$.decidedBy").value(analyst.toString()));
        assertThat(host.opened(f.repository())).isEmpty();
    }

    @Test
    void anApprovalIsOneShot() throws Exception {
        Fixture f = fixture();
        String id = proposeAsAnalyst(f);

        mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", approver))).andExpect(status().isOk());
        mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", approver)))
                .andExpect(status().isConflict());
        mvc.perform(post(PATH + "/{id}/dismiss", id).with(as("ANALYST", approver))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"too late\"}"))
                .andExpect(status().isConflict());
        // The opened one is still live, so the same upgrade cannot be drafted twice.
        mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON).content(proposal(f)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("already opened")));
        assertThat(host.opened(f.repository())).hasSize(1);
    }

    @Test
    void aDismissalRecordsWhoAndWhyAndFreesTheSlot() throws Exception {
        Fixture f = fixture();
        String id = proposeAsAnalyst(f);

        mvc.perform(post(PATH + "/{id}/dismiss", id).with(as("ANALYST", approver))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PATH + "/{id}/dismiss", id).with(as("ANALYST", approver))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" Upgrading with the next release \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"))
                .andExpect(jsonPath("$.dismissReason").value("Upgrading with the next release"))
                .andExpect(jsonPath("$.decidedBy").value(approver.toString()));
        mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", approver)))
                .andExpect(status().isConflict());

        mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON).content(proposal(f)))
                .andExpect(status().isCreated());
        mvc.perform(get(PATH).param("serviceId", f.service().getId().toString()).param("status", "DISMISSED")
                        .with(as("SERVICE_OWNER", UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].serviceName").value(f.service().getName()));
        // What the change-impact page asks for: this change's proposals in several statuses at once.
        mvc.perform(get(PATH).param("changeSignalId", f.advisory().id().toString()).param("status", "PROPOSED,DISMISSED")
                        .with(as("ANALYST", analyst)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[1].id").value(id));
        mvc.perform(get(PATH).param("changeSignalId", f.advisory().id().toString()).param("status", "OPENED")
                        .with(as("ANALYST", analyst)))
                .andExpect(jsonPath("$.content.length()").value(0));
        assertThat(host.opened(f.repository())).isEmpty();
    }

    @Test
    void aFailedOpeningIsRecordedAndCanBeApprovedAgain() throws Exception {
        Fixture f = fixture();
        String id = proposeAsAnalyst(f);

        host.failNextOpen(new ApiExceptions.UpstreamUnavailable("GitHub is unavailable"));
        mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", approver)))
                .andExpect(status().isBadGateway());
        mvc.perform(get(PATH + "/{id}", id).with(as("ANALYST", approver)))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureMessage").value("GitHub is unavailable"));

        mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", approver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPENED"))
                .andExpect(jsonPath("$.failureMessage").value(nullValue()));
    }

    @Test
    void aManifestThatDoesNotDeclareTheDependencyFailsAtApprovalWithTheReason() throws Exception {
        Fixture f = fixture();
        host.put(f.repository(), "pom.xml", "<project><dependencies/></project>");
        String id = proposeAsAnalyst(f);

        mvc.perform(post(PATH + "/{id}/approve", id).with(as("ANALYST", approver)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("is not declared in pom.xml")));
        mvc.perform(get(PATH + "/{id}", id).with(as("ANALYST", approver)))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureMessage").value(containsString("is not declared in pom.xml")));
        assertThat(host.opened(f.repository())).isEmpty();
    }

    @Test
    void anAiOutageNeverBlocksTheFix() throws Exception {
        Fixture f = fixture();
        drafter.unavailable(true);
        try {
            mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON).content(proposal(f)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.toVersion").value("1.3.0"))
                    .andExpect(jsonPath("$.body").value(containsString("_No AI rationale: the AI service could not draft one._")));
        } finally {
            drafter.unavailable(false);
        }
    }

    @Test
    void refusesWhatItCannotUpgradeSafely() throws Exception {
        Fixture f = fixture();

        BusinessService unlinked = inventory.createBusinessService(
                "Unlinked " + f.tag(), BusinessCriticality.LOW, ServiceExposure.ISOLATED);
        inventory.addServiceComponent(unlinked.getId(), f.component().getId());
        expectConflict(request(f.advisory().id(), unlinked.getId(), f.component().getId()), "Link the service's repository");

        SoftwareComponent framework = inventory.createSoftwareComponent("maven", "com.acme:framework-" + f.tag(), "3.0.0");
        inventory.addComponentDependency(framework.getId(), f.component().getId());
        BusinessService indirect = inventory.createBusinessService(
                "Indirect " + f.tag(), BusinessCriticality.HIGH, ServiceExposure.INTERNAL);
        inventory.linkServiceRepository(indirect.getId(), hosted("acme/indirect-" + f.tag()), null);
        inventory.addServiceComponent(indirect.getId(), framework.getId());
        expectConflict(request(f.advisory().id(), indirect.getId(), f.component().getId()), "transitive upgrades are not automated");

        SoftwareComponent unrelated = inventory.createSoftwareComponent("maven", "com.acme:unrelated-" + f.tag(), "1.0.0");
        inventory.addServiceComponent(f.service().getId(), unrelated.getId());
        expectConflict(request(f.advisory().id(), f.service().getId(), unrelated.getId()), "not one this change matches");

        proposeAsAnalyst(f);
        expectConflict(proposal(f), "already proposed");

        mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                        .content(request(UUID.randomUUID(), f.service().getId(), f.component().getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherOrganizationSeesNoneOfIt() throws Exception {
        Fixture f = fixture();
        UUID id = UUID.fromString(proposeAsAnalyst(f));

        Throwable[] seen = new Throwable[1];
        long[] listed = {-1};
        currentOrg.runAs(UUID.randomUUID(), () -> {
            listed[0] = proposals.list(null, null, null, Pageable.unpaged()).getTotalElements();
            try {
                proposals.get(id);
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
        String tag = Long.toHexString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
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

    /** The fake repository host serves gitlab.com (see FakeRepositoryHost). */
    private static RepositoryUrl hosted(String repository) {
        return RepositoryUrl.parse("https://gitlab.com/" + repository, List.of("gitlab.com"));
    }

    private String proposeAsAnalyst(Fixture f) throws Exception {
        return id(mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON)
                        .content(proposal(f)))
                .andExpect(status().isCreated()));
    }

    private void expectConflict(String body, String message) throws Exception {
        mvc.perform(post(PATH).with(as("ANALYST", analyst)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString(message)));
    }

    private static String proposal(Fixture f) {
        return request(f.advisory().id(), f.service().getId(), f.component().getId());
    }

    private static String request(UUID changeSignalId, UUID serviceId, UUID componentId) {
        return "{\"changeSignalId\":\"%s\",\"serviceId\":\"%s\",\"componentId\":\"%s\"}"
                .formatted(changeSignalId, serviceId, componentId);
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
