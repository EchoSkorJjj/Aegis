package com.aegis.core.actions.triage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aegis.core.actions.assess.ReachabilityAssessment;
import com.aegis.core.actions.assess.ReachabilityAssessmentStore;
import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.ai.AiDtos;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.platform.tenant.CurrentOrg;
import com.aegis.core.support.AbstractIntegrationTest;
import com.aegis.core.support.FakeAiAssessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Through the real queue, store and row-level security: the answer is kept, shown beside the finding, and filterable. */
class FindingInterpretationIntegrationTest extends AbstractIntegrationTest {

    private static final String PATH = "/api/findings";
    private static final AiDtos.Interpretation REAL = new AiDtos.Interpretation("LIKELY_REAL", "MEDIUM",
            "A flaw lets crafted input run code.", "The service is internet-facing.", "Remote code execution.", "ACT_NOW",
            "Upgrade now.", "The feature is enabled.", "YES", List.of("The feature is enabled."),
            List.of("The advisory's own words."));

    @Autowired private CurrentOrg currentOrg;
    @Autowired private InventoryService inventory;
    @Autowired private AdvisoryStore advisories;
    @Autowired private FindingInterpretations interpretations;
    @Autowired private FakeAiAssessor assessor;
    @Autowired private ReachabilityAssessmentStore assessments;

    private UUID org;
    private BusinessService service;
    private Advisory advisory;
    private String library;

    @BeforeEach
    void estate() {
        assessor.interprets(REAL);
        assessor.clearInterpretations();
        org = UUID.randomUUID();
        String tag = UUID.randomUUID().toString();
        library = "interpret-lib-" + tag;
        currentOrg.runAs(org, () -> {
            service = inventory.createBusinessService("Interpreted " + tag, BusinessCriticality.CRITICAL,
                    ServiceExposure.INTERNET_FACING);
            SoftwareComponent component = inventory.createSoftwareComponent("npm", library, "1.0.0");
            inventory.addServiceComponent(service.getId(), component.getId());
        });
        Instant old = Instant.parse("2021-01-01T00:00:00Z");
        advisory = advisories.upsert(new Advisory(null, "nvd", "CVE-2098-" + tag, "A flaw in " + library, old, old,
                new Advisory.Cvss("3.1", null, new BigDecimal("10.0"), "CRITICAL"),
                List.of(AffectedProduct.ofPackage("npm", library, null, "0", null, null, "2.0.0"))));
    }

    @AfterEach
    void answerAgain() {
        assessor.interprets(REAL);
    }

    @Test
    void theAnswerIsKeptAndShownBesideTheFinding() throws Exception {
        assertThat(read()).isEqualTo(1);

        mvc.perform(get(PATH + "/interpretations").param("changeSignalId", advisory.id().toString())
                        .with(as("ANALYST", UUID.randomUUID(), org)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].serviceId").value(service.getId().toString()))
                .andExpect(jsonPath("$[0].subject").value(library))
                .andExpect(jsonPath("$[0].signal").value("LIKELY_REAL"))
                .andExpect(jsonPath("$[0].confidence").value("MEDIUM"))
                .andExpect(jsonPath("$[0].whatChanged").value(REAL.whatChanged()))
                .andExpect(jsonPath("$[0].whyItMatters").value(REAL.whyItMatters()))
                .andExpect(jsonPath("$[0].whatNext").value(REAL.whatNext()))
                .andExpect(jsonPath("$[0].suggestedAction").value("ACT_NOW"))
                .andExpect(jsonPath("$[0].actionDetail").value(REAL.actionDetail()))
                .andExpect(jsonPath("$[0].condition").value("The feature is enabled."))
                .andExpect(jsonPath("$[0].conditionMet").value("YES"))
                .andExpect(jsonPath("$[0].assumptions[0]").value("The feature is enabled."))
                .andExpect(jsonPath("$[0].citations[0]").value("The advisory's own words."))
                .andExpect(jsonPath("$[0].interpretedAt").exists())
                .andExpect(jsonPath("$[0].fingerprint").doesNotExist());
    }

    @Test
    void theQueueCarriesTheSignalAndCanFilterOnIt() throws Exception {
        mvc.perform(get(PATH).with(as("ANALYST", UUID.randomUUID(), org)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.content[0].interpretation").value(nullValue()));

        read();

        mvc.perform(get(PATH).with(as("ANALYST", UUID.randomUUID(), org)))
                .andExpect(jsonPath("$.items.content[0].interpretation.signal").value("LIKELY_REAL"))
                .andExpect(jsonPath("$.items.content[0].interpretation.confidence").value("MEDIUM"));
        mvc.perform(get(PATH).param("signal", "LIKELY_REAL").with(as("ANALYST", UUID.randomUUID(), org)))
                .andExpect(jsonPath("$.items.content[*].service.id").value(contains(service.getId().toString())));
        mvc.perform(get(PATH).param("signal", "LIKELY_NOISE").with(as("ANALYST", UUID.randomUUID(), org)))
                .andExpect(jsonPath("$.items.totalElements").value(0));
    }

    @Test
    void theAnswerNeverMovesTheScore() throws Exception {
        String before = mvcScore();

        read();

        assertThat(mvcScore()).isEqualTo(before);
    }

    @Test
    void anotherOrganizationSeesNothing() throws Exception {
        read();

        mvc.perform(get(PATH + "/interpretations").param("changeSignalId", advisory.id().toString())
                        .with(as("ANALYST", UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void readsTheCodeAssessmentsKeptForAService() {
        ReachabilityAssessment kept = new ReachabilityAssessment(UUID.randomUUID(), service.getId(), "npm", library,
                "CVE-2098-kept", "abc123", ReachabilityAssessment.Verdict.CANT_TELL, "No proxy seen.", List.of(), List.of(),
                List.of(), null, Instant.now());
        currentOrg.runAs(org, () -> assessments.save(kept));

        List<ReachabilityAssessment>[] found = new List[1];
        currentOrg.runAs(org, () -> found[0] = assessments.findForServices(List.of(service.getId())));
        List<ReachabilityAssessment>[] elsewhere = new List[1];
        currentOrg.runAs(UUID.randomUUID(), () -> elsewhere[0] = assessments.findForServices(List.of(service.getId())));

        assertThat(found[0]).extracting(ReachabilityAssessment::rationale).containsExactly("No proxy seen.");
        assertThat(elsewhere[0]).isEmpty();
    }

    @Test
    void aSecondRunLeavesTheAnswerAlone() {
        read();

        read();

        assertThat(assessor.interpretations()).hasSize(1);
    }

    private int read() {
        int[] done = new int[1];
        currentOrg.runAs(org, () -> done[0] = interpretations.interpretNext(5).interpreted());
        return done[0];
    }

    private String mvcScore() throws Exception {
        return com.jayway.jsonpath.JsonPath.read(mvc.perform(get(PATH).with(as("ANALYST", UUID.randomUUID(), org)))
                .andReturn().getResponse().getContentAsString(), "$.items.content[0].score").toString();
    }

    private static RequestPostProcessor as(String role, UUID user, UUID org) {
        return jwt().jwt(builder -> builder.subject(user.toString()).claim("org", org.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
