package com.aegis.core.actions.assess;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.ai.AiDtos;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.ImportScan;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.RepositoryUrl;
import com.aegis.core.inventory.domain.SbomImportSource;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.sbom.FakeGitAndSyft;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.tenant.CurrentOrg;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import com.aegis.core.support.FakeAiAssessor;
import com.aegis.core.support.TestOwners;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The AI on whether a service's code uses the vulnerable part of a package (E11-5): asked for an item, the AI first names
 * what to search for from the advisory and its fix commit; Aegis reads the code at the commit the import read — never
 * stored — for where it uses what it imports and where it names those terms; the AI's answer is kept with the lines it
 * cites, its checks and what was searched, as a suggestion that changes nothing. Only the advisory, what its fix changed
 * and those lines leave Aegis, redacted; anything it can't answer says why.
 */
@TestPropertySource(properties = "aegis.assess.fix-commits.enabled=true")
class ReachabilityAssessmentIntegrationTest extends AbstractInventoryIntegrationTest {

    private static final String FIX = "https://github.com/acme/lib/commit/cc6756b61ed90b6b9227f670e0408d5d9bd48551";

    /** The fix commit's diff, from its host — faked: no test reaches the network. */
    @TestConfiguration
    static class FixCommitDiffs {
        @Bean
        @Primary
        DiffSource fixCommitDiffs() {
            return uri -> uri.toString().equals(FIX + ".diff") ? java.util.Optional.of("""
                    diff --git a/lib/merge.js b/lib/merge.js
                    @@ -10,6 +10,8 @@ function merge(target, source)
                    +  if (key === '__proto__') continue;
                    """) : java.util.Optional.empty();
        }
    }

    private static final String PATH = "/api/actions/reachability-assessments";
    private static final String FILE = """
            // the app
            import express from 'express';
            import { merge } from '%s';
            const db = connect('password=opaque-credential');
            merge(a, b);
            """;

    @Autowired private InventoryService inventory;
    @Autowired private InventoryStore store;
    @Autowired private AdvisoryStore advisories;
    @Autowired private TestOwners owners;
    @Autowired private CurrentOrg currentOrg;
    @Autowired private FakeAiAssessor assessor;
    @Autowired private FakeGitAndSyft git;

    @AfterEach
    void answerAgain() {
        assessor.answers(FakeAiAssessor.NOT_CALLED);
    }

    @Test
    void askingKeepsTheAnswerWithTheLinesItCites() throws Exception {
        Item item = item();
        assertThat(listed(item.used())).containsEntry("status", "NOT_ASSESSED").containsEntry("assessable", true)
                .containsEntry("reason", "Nobody has asked the AI yet");

        Map<String, Object> asked = ask(item, as("ANALYST", UUID.randomUUID()));

        assertThat(asked).containsEntry("status", "ASSESSED").containsEntry("verdict", "NOT_CALLED")
                .containsEntry("rationale", FakeAiAssessor.NOT_CALLED.rationale())
                .containsEntry("ecosystem", "npm").containsEntry("packageName", item.packageName());
        // A file this short is sent whole; the link points at where it uses what it imports (merge, line 5).
        assertThat(JsonPath.<Map<String, Object>>read(asked, "$.citedLines[0]")).containsEntry("path", "src/app.ts")
                .containsEntry("startLine", 1).containsEntry("endLine", 5).containsEntry("line", 5)
                .containsEntry("url", "https://github.com/acme/" + item.repository() + "/blob/" + FakeGitAndSyft.REVISION
                        + "/src/app.ts#L5");
        // Kept: the item detail shows it without asking again.
        assertThat(listed(item.used())).containsEntry("status", "ASSESSED").containsEntry("verdict", "NOT_CALLED");

        AiDtos.AssessmentRequest sent = assessor.requests().getLast();
        assertThat(sent.pkg()).isEqualTo(new AiDtos.PackageRef("npm", item.packageName()));
        assertThat(sent.advisoryText()).contains("prototype pollution in merge()")
                .contains("Merging untrusted objects lets an attacker set __proto__.");
        assertThat(sent.excerpts()).singleElement().satisfies(excerpt -> {
            assertThat(excerpt.id()).isEqualTo("E1");
            assertThat(excerpt.path()).isEqualTo("src/app.ts");
            assertThat(excerpt.startLine()).isEqualTo(1);
            assertThat(excerpt.kind()).isEqualTo("use");
            assertThat(excerpt.text()).contains("merge(a, b);");
        });
    }

    @Test
    void theAdvisorysFixCommitAndTheTermsPass1NamesShapeWhatPass2Reads() throws Exception {
        Item item = item();
        git.willHold(item.repository(), "src/util.ts", "const helpers = load();\nexport const copy = helpers.deepMerge(x);\n");
        assessor.answers(new AiDtos.Assessment("CANT_TELL", "Whether deepMerge receives request input isn't shown.",
                List.of("Whether deepMerge is given request bodies"), List.of()));
        assessor.looksFor(List.of("deepMerge", "neverWritten"));

        Map<String, Object> asked = ask(item, as("ANALYST", UUID.randomUUID()));

        // Pass 1 read the advisory and what its fix changed; nothing from the service.
        AiDtos.LookForRequest pass1 = assessor.lookFors().getLast();
        assertThat(pass1.fixSummary()).isEqualTo("lib/merge.js: changed in function merge(target, source)");
        assertThat(pass1.toString()).doesNotContain("src/app.ts", "src/util.ts");
        // Pass 2: the use, the search's findings, and where the code names the term.
        AiDtos.AssessmentRequest pass2 = assessor.requests().getLast();
        assertThat(pass2.fixSummary()).isEqualTo(pass1.fixSummary());
        assertThat(pass2.searches()).containsExactly(new AiDtos.Search("deepMerge", 2, 1, 1, true),
                new AiDtos.Search("neverWritten", 2, 0, 0, true));
        assertThat(pass2.excerpts()).extracting(AiDtos.CodeExcerpt::id, AiDtos.CodeExcerpt::path,
                AiDtos.CodeExcerpt::kind, AiDtos.CodeExcerpt::term).containsExactly(
                org.assertj.core.groups.Tuple.tuple("E1", "src/app.ts", "use", null),
                org.assertj.core.groups.Tuple.tuple("E2", "src/util.ts", "match", "deepMerge"));
        // Kept and shown: the answer, what to check, and what was searched for — not the code.
        assertThat(asked).containsEntry("verdict", "CANT_TELL")
                .containsEntry("checks", List.of("Whether deepMerge is given request bodies"));
        assertThat(JsonPath.<List<Map<String, Object>>>read(asked, "$.searches")).containsExactly(
                Map.of("term", "deepMerge", "lines", 1, "files", 1), Map.of("term", "neverWritten", "lines", 0, "files", 0));
        assertThat(listed(item.used())).containsEntry("checks", List.of("Whether deepMerge is given request bodies"));
    }

    @Test
    void whenPass1sAnswerCantBeUsedTheCodeIsStillAssessedWithoutASearch() throws Exception {
        Item item = item();
        assessor.failsToLookFor(new ApiExceptions.UpstreamUnavailable(
                "The AI service could not complete the request (HTTP 502)"));

        assertThat(ask(item, as("ANALYST", UUID.randomUUID()))).containsEntry("status", "ASSESSED")
                .containsEntry("searches", List.of());
        assertThat(assessor.requests().getLast().searches()).isEmpty();
    }

    @Test
    void onlyTheAdvisoryAndAFewLinesLeaveAegisWithTheirSecretsRedacted() throws Exception {
        Item item = item();

        ask(item, as("ANALYST", UUID.randomUUID()));

        String sent = assessor.requests().getLast().toString() + assessor.lookFors().getLast();
        assertThat(sent).contains("[REDACTED]")
                .doesNotContain("opaque-credential", "github.com/acme", item.service().getName());
    }

    @Test
    void whenTheAiIsUnavailableTheItemIsNotAssessedAndNothingIsKept() throws Exception {
        Item item = item();
        assessor.fails(new ApiExceptions.UpstreamUnavailable("The AI service could not complete the request (HTTP 503)"));

        assertThat(ask(item, as("ANALYST", UUID.randomUUID()))).containsEntry("status", "NOT_ASSESSED")
                .containsEntry("reason", "The AI service is unavailable; try again later");
        assertThat(listed(item.used())).containsEntry("status", "NOT_ASSESSED")
                .containsEntry("reason", "Nobody has asked the AI yet");
    }

    @Test
    void whenTheAiIsStillAtItsPerMinuteLimitTheItemSaysSoNotThatItIsDown() throws Exception {
        Item item = item();
        assessor.fails(new ApiExceptions.UpstreamUnavailable("The AI service could not complete the request (HTTP 429)"));

        assertThat(ask(item, as("ANALYST", UUID.randomUUID()))).containsEntry("status", "NOT_ASSESSED")
                .containsEntry("reason", "The AI is at its per-minute limit, which every assessment and draft shares;"
                        + " ask again in a minute");
    }

    @Test
    void aPackageTheCodeDoesNotImportItselfIsNeverSentToTheAi() throws Exception {
        Item item = item();
        int before = assessor.requests().size();

        Map<String, Object> deep = listed(item.deep());
        assertThat(deep).containsEntry("status", "NOT_ASSESSED").containsEntry("assessable", false);
        assertThat((String) deep.get("reason")).contains("comes in through " + item.packageName());
        String body = mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(request(item.service().getId(), item.deep())).with(as("ANALYST", UUID.randomUUID())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(body, "$[0].status")).isEqualTo("NOT_ASSESSED");
        assertThat(assessor.requests()).hasSize(before);
    }

    @Test
    void askingAgainReplacesTheAnswer() throws Exception {
        Item item = item();
        ask(item, as("ANALYST", UUID.randomUUID()));
        assessor.answers(new AiDtos.Assessment("CANT_TELL", "The lines don't show what is used.", List.of(), List.of()));

        ask(item, as("ANALYST", UUID.randomUUID()));

        String body = mvc.perform(get(PATH).param("changeSignalId", item.used().toString())
                        .with(as("COMPLIANCE_ADMIN", UUID.randomUUID())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$[*].verdict")).containsExactly("CANT_TELL");
    }

    @Test
    void anAnalystOrTheServicesOwnerMayAskAndEveryDomainRoleMayRead() throws Exception {
        Item item = item();
        Owner owner = owners.assign(item.service().getId(), "Owner " + item.packageName());

        for (RequestPostProcessor refused : List.of(as("COMPLIANCE_ADMIN", UUID.randomUUID()),
                as("SERVICE_OWNER", UUID.randomUUID()), as("USER", UUID.randomUUID()))) {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                    .content(request(item.service().getId(), item.used())).with(refused))
                    .andExpect(status().isForbidden());
        }
        assertThat(ask(item, as("SERVICE_OWNER", owner.getUserId()))).containsEntry("status", "ASSESSED");
        mvc.perform(get(PATH).param("changeSignalId", item.used().toString()).with(as("COMPLIANCE_ADMIN", UUID.randomUUID())))
                .andExpect(status().isOk());
        mvc.perform(get(PATH).param("changeSignalId", item.used().toString()).with(as("USER", UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void aDraftedTicketCarriesTheSuggestionButNeverTheLines() throws Exception {
        Item item = item();

        String ticket = mvc.perform(post("/api/actions/risk-tickets").contentType(MediaType.APPLICATION_JSON)
                        .content(request(item.service().getId(), item.used())).with(as("ANALYST", UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        List<String> evidence = JsonPath.read(ticket, "$.evidence");
        assertThat(evidence).contains("AI reachability suggestion for " + item.packageName()
                + ": the code doesn't use the vulnerable part. "
                + FakeAiAssessor.NOT_CALLED.rationale() + " A suggestion: it doesn't change the score.");
        assertThat(evidence).noneMatch(line -> line.contains("src/app.ts"));
        // Drafting asked, so the item detail has the answer too.
        assertThat(listed(item.used())).containsEntry("status", "ASSESSED");
    }

    @Test
    void anotherOrganizationSeesNoneOfIt() throws Exception {
        Item item = item();
        ask(item, as("ANALYST", UUID.randomUUID()));

        currentOrg.runAs(UUID.randomUUID(), () -> assertThatCode(() -> {
            String body = mvc.perform(get(PATH).param("changeSignalId", item.used().toString())
                            .with(as("ANALYST", UUID.randomUUID())))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<Object>>read(body, "$")).isEmpty();
        }).doesNotThrowAnyException());
    }

    // ---- fixture -----------------------------------------------------------------------------------

    /**
     * A critical service whose repository import linked {@code used-<tag>} — imported by {@code src/app.ts} on line 3 —
     * which pulls in {@code deep-<tag>}; each has a vulnerability. The repository holds that file at the commit read.
     */
    private Item item() {
        String tag = Long.toHexString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        String repository = "assess-" + tag;
        BusinessService service = inventory.createBusinessService(
                "Assess " + tag, BusinessCriticality.CRITICAL, ServiceExposure.INTERNET_FACING);
        inventory.linkServiceRepository(service.getId(),
                RepositoryUrl.parse("https://github.com/acme/" + repository, List.of("github.com")), null);
        SoftwareComponent used = inventory.createSoftwareComponent("npm", "used-" + tag, "1.0.0");
        SoftwareComponent deep = inventory.createSoftwareComponent("npm", "deep-" + tag, "1.0.0");
        store.linkRepositoryComponent(service.getId(), used.getId(), List.of("package.json"), List.of("package-lock.json"));
        inventory.addComponentDependency(used.getId(), deep.getId());
        Advisory usedAdvisory = advisory("CVE-2099-" + tag + "1", used.getName(), "prototype pollution in merge()",
                "Merging untrusted objects lets an attacker set __proto__.", List.of(FIX));
        Advisory deepAdvisory = advisory("CVE-2099-" + tag + "2", deep.getName(), "denial of service in parse()", null,
                List.of());

        BusinessService scanned = inventory.getBusinessService(service.getId());
        scanned.recordSbomImport(SbomImportSource.PUBLIC, Instant.now());
        scanned.recordImportScan(new ImportScan(FakeGitAndSyft.REVISION, List.of("npm"), 1, null, List.of()), Instant.now());
        store.save(scanned);
        store.replaceServiceImports(service.getId(), List.of(new ImportScan.ModuleImports("npm", used.getName(), 1,
                List.of(new ImportScan.Site("src/app.ts", 3)))));
        git.willHold(repository, "src/app.ts", FILE.formatted(used.getName()));
        return new Item(scanned, repository, used.getName(), usedAdvisory.id(), deepAdvisory.id());
    }

    private record Item(BusinessService service, String repository, String packageName, UUID used, UUID deep) {
    }

    private Advisory advisory(String cve, String packageName, String flaw, String details, List<String> fixCommits) {
        return advisories.upsert(new Advisory(null, "osv", "GHSA-" + cve, cve, packageName + " before 1.1.0: " + flaw + ".",
                Instant.now(), Instant.now(), new Advisory.Cvss("3.1", null, new BigDecimal("9.0"), null),
                List.of(AffectedProduct.ofPackage("npm", packageName, null, "1.0.0", null, null, "1.1.0")), details,
                fixCommits));
    }

    private Map<String, Object> listed(UUID changeSignalId) throws Exception {
        String body = mvc.perform(get(PATH).param("changeSignalId", changeSignalId.toString())
                        .with(as("ANALYST", UUID.randomUUID())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[0]");
    }

    private Map<String, Object> ask(Item item, RequestPostProcessor caller) throws Exception {
        String body = mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(request(item.service().getId(), item.used())).with(caller))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[0]");
    }

    private static String request(UUID serviceId, UUID changeSignalId) {
        return "{\"serviceId\":\"" + serviceId + "\",\"changeSignalId\":\"" + changeSignalId + "\"}";
    }

    private static RequestPostProcessor as(String role, UUID userId) {
        return jwt().jwt(token -> token.subject(userId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
