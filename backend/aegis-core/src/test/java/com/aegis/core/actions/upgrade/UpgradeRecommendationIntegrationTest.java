package com.aegis.core.actions.upgrade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.advisories.AffectedProduct;
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
import com.aegis.core.matching.MatchingDtos.ComponentRef;
import com.aegis.core.matching.UpgradeTargets;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.tenant.CurrentOrg;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import com.aegis.core.support.TestOwners;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Upgrade recommendations: every vulnerable dependency of a service, found from the service's side with no
 * CVE to look up, with the fix and whether Aegis can draft it. Each test builds its own uniquely named
 * estate and only ever looks at its own services' items.
 */
class UpgradeRecommendationIntegrationTest extends AbstractInventoryIntegrationTest {

    private static final String PATH = "/api/actions/upgrade-recommendations";

    @Autowired private InventoryService inventory;
    @Autowired private InventoryStore store;
    @Autowired private AdvisoryStore advisories;
    @Autowired private TestOwners owners;
    @Autowired private CurrentOrg currentOrg;
    @Autowired private UpgradeTargets targets;

    @Test
    void everyVulnerableDependencyIsListedWithItsFixMostImpactFirst() throws Exception {
        Estate estate = estate();

        List<Map<String, Object>> items = recommendations(estate.service().getId(), as("ANALYST", UUID.randomUUID()));

        assertThat(items).extracting(item -> JsonPath.<String>read(item, "$.component.name"))
                .containsExactly(estate.lib(), estate.deep(), estate.crate(), estate.stuck());
        Map<String, Object> lib = items.getFirst();
        assertThat(lib).containsEntry("readiness", "READY").containsEntry("toVersion", "1.2.0")
                .containsEntry("changeSignalId", estate.advisory().id().toString())
                .containsEntry("manifestPaths", List.of("svc/pom.xml")).containsEntry("canDraft", true)
                .containsEntry("serviceName", estate.service().getName()).containsEntry("reason", null)
                .containsEntry("overrideIn", List.of());
        assertThat(JsonPath.<String>read(lib, "$.vulnerabilities[0].id")).isEqualTo("CVE-2099-" + estate.tag() + "1");
        assertThat(JsonPath.<Double>read(lib, "$.score")).isPositive();

        // Pulled in by the library the pom declares: the pom overrides it.
        Map<String, Object> deep = items.get(1);
        assertThat(deep).containsEntry("readiness", "READY").containsEntry("toVersion", "2.1.0")
                .containsEntry("overrideIn", List.of("svc/pom.xml")).containsEntry("reason", null);
        assertThat(JsonPath.<String>read(deep, "$.via.name")).isEqualTo(estate.lib());
        assertThat(items.get(2)).containsEntry("readiness", "UNSUPPORTED");
        assertThat(items.get(3)).containsEntry("readiness", "NO_FIX");
        assertThat((String) items.get(3).get("reason")).contains("publishes no fixed version");
    }

    @Test
    void aServiceWithoutItsRepositoryIsToldToLinkItAndTheWholeOrganizationIsOneList() throws Exception {
        Estate estate = estate();
        BusinessService unlinked = inventory.createBusinessService(
                "Unlinked " + estate.tag(), BusinessCriticality.LOW, ServiceExposure.ISOLATED);
        inventory.addServiceComponent(unlinked.getId(), estate.libId());

        List<Map<String, Object>> items = recommendations(unlinked.getId(), as("ANALYST", UUID.randomUUID()));
        // The library it uses directly, and the one that library pulls in.
        assertThat(items).extracting(item -> JsonPath.<String>read(item, "$.component.name"))
                .containsExactly(estate.lib(), estate.deep());
        assertThat(items.getFirst()).containsEntry("readiness", "NO_REPOSITORY").containsEntry("toVersion", "1.2.0")
                .containsEntry("reason", "Link the service's repository so Aegis can draft the pull request");

        List<Map<String, Object>> organization = recommendations(null, as("COMPLIANCE_ADMIN", UUID.randomUUID()));
        List<String> ours = organization.stream().map(item -> (String) item.get("serviceId"))
                .filter(id -> id.equals(estate.service().getId().toString()) || id.equals(unlinked.getId().toString()))
                .distinct().toList();
        assertThat(ours).hasSize(2);
        // The critical, internet-facing service outranks the isolated, low one for the same library.
        assertThat(organization.stream().map(item -> (String) item.get("serviceId")).toList())
                .containsSubsequence(estate.service().getId().toString(), unlinked.getId().toString());
    }

    @Test
    void onlyAnalystsAndTheServicesOwnersAreOfferedTheDraft() throws Exception {
        Estate estate = estate();
        Owner owner = owners.assign(estate.service().getId(), "Recommendation Owner " + estate.tag());

        assertThat(recommendations(estate.service().getId(), serviceOwner(UUID.randomUUID())))
                .allSatisfy(item -> assertThat(item).containsEntry("canDraft", false));
        // A compliance admin audits the decisions rather than drafting them.
        assertThat(recommendations(estate.service().getId(), as("COMPLIANCE_ADMIN", UUID.randomUUID())))
                .isNotEmpty().allSatisfy(item -> assertThat(item).containsEntry("canDraft", false));
        assertThat(recommendations(estate.service().getId(), serviceOwner(owner.getUserId())))
                .allSatisfy(item -> assertThat(item).containsEntry("canDraft", true));
        assertThat(recommendations(estate.service().getId(), as("ANALYST", UUID.randomUUID())))
                .allSatisfy(item -> assertThat(item).containsEntry("canDraft", true));
        mvc.perform(get(PATH).with(as("USER", UUID.randomUUID()))).andExpect(status().isForbidden());
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).param("serviceId", UUID.randomUUID().toString()).with(as("ANALYST", UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void draftingAResponseUsesTheRecordedManifestAndTheRecommendationThenShowsIt() throws Exception {
        Estate estate = estate();
        List<Map<String, Object>> before = recommendations(estate.service().getId(), as("ANALYST", UUID.randomUUID()));
        assertThat(before).allSatisfy(item -> assertThat(item).containsEntry("response", null));
        Map<String, Object> lib = before.getFirst();

        String created = mvc.perform(post("/api/actions/risk-tickets").with(as("ANALYST", UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content(response(lib.get("changeSignalId"), estate)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pullRequests[0].edits[0].manifestPath").value("svc/pom.xml"))
                .andExpect(jsonPath("$.pullRequests[0].edits[0].toVersion").value("1.2.0"))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> after = recommendations(estate.service().getId(), as("ANALYST", UUID.randomUUID())).getFirst();
        assertThat(JsonPath.<String>read(after, "$.proposal.id"))
                .isEqualTo(JsonPath.<String>read(created, "$.pullRequests[0].id"));
        assertThat(JsonPath.<String>read(after, "$.proposal.status")).isEqualTo("PROPOSED");
        assertThat(JsonPath.<String>read(after, "$.response.ticketId")).isEqualTo(JsonPath.<String>read(created, "$.id"));
        assertThat(JsonPath.<String>read(after, "$.response.status")).isEqualTo("PROPOSED");
    }

    @Test
    void whatCannotBeUpgradedStillGetsAResponseATicketAlone() throws Exception {
        Estate estate = estate();
        Map<String, Object> crate = recommendations(estate.service().getId(), as("ANALYST", UUID.randomUUID())).stream()
                .filter(item -> "UNSUPPORTED".equals(item.get("readiness"))).findFirst().orElseThrow();
        assertThat(crate.get("changeSignalId")).as("a vulnerability to respond to").isNotNull();

        String created = mvc.perform(post("/api/actions/risk-tickets").with(as("ANALYST", UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content(response(crate.get("changeSignalId"), estate)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pullRequests").isEmpty())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> after = recommendations(estate.service().getId(), as("ANALYST", UUID.randomUUID())).stream()
                .filter(item -> "UNSUPPORTED".equals(item.get("readiness"))).findFirst().orElseThrow();
        assertThat(after).containsEntry("proposal", null);
        assertThat(JsonPath.<String>read(after, "$.response.ticketId")).isEqualTo(JsonPath.<String>read(created, "$.id"));
    }

    /**
     * A dependency the repository declares only in a file Aegis doesn't edit — a Gradle lockfile, poetry.lock — gets
     * a ticket saying where it lives, never a pull request against a guessed root manifest; one declared in a pom as
     * well gets its pom upgraded, and the ticket names the file left for a person.
     */
    @Test
    void aDependencyFoundOnlyInFilesAegisDoesNotEditGetsATicketSayingWhere() throws Exception {
        String tag = Long.toHexString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        BusinessService service = inventory.createBusinessService(
                "Gradle " + tag, BusinessCriticality.CRITICAL, ServiceExposure.INTERNET_FACING);
        inventory.linkServiceRepository(service.getId(),
                RepositoryUrl.parse("https://gitlab.com/acme/gradle-" + tag, List.of("gitlab.com")), null);
        String gradleLib = "com.acme:gradle-lib-" + tag;
        String poetryLib = "poetry-lib-" + tag;
        String mixedLib = "com.acme:mixed-lib-" + tag;
        // As an import from the repository records them: the file syft found each one in.
        store.linkRepositoryComponent(service.getId(),
                inventory.createSoftwareComponent("maven", gradleLib, "1.0.0").getId(), List.of("app/gradle.lockfile"));
        store.linkRepositoryComponent(service.getId(),
                inventory.createSoftwareComponent("pypi", poetryLib, "1.0.0").getId(), List.of("poetry.lock"));
        store.linkRepositoryComponent(service.getId(), inventory.createSoftwareComponent("maven", mixedLib, "1.0.0")
                .getId(), List.of("svc/pom.xml", "app/gradle.lockfile"));
        advisory("CVE-2099-" + tag + "1", "9.1", AffectedProduct.ofPackage("Maven", gradleLib, null, "1.0.0", null, null, "1.2.0"));
        advisory("CVE-2099-" + tag + "2", "8.1", AffectedProduct.ofPackage("PyPI", poetryLib, null, "1.0.0", null, null, "1.2.0"));
        advisory("CVE-2099-" + tag + "3", "7.1", AffectedProduct.ofPackage("Maven", mixedLib, null, "1.0.0", null, null, "1.2.0"));

        Map<String, Map<String, Object>> byName = recommendations(service.getId(), as("ANALYST", UUID.randomUUID()))
                .stream().collect(Collectors.toMap(item -> JsonPath.read(item, "$.component.name"), item -> item));

        assertThat(byName.get(gradleLib)).containsEntry("readiness", "UNSUPPORTED")
                .containsEntry("reason", "Found in app/gradle.lockfile, which Aegis doesn't edit; upgrade it by hand");
        assertThat(byName.get(poetryLib)).containsEntry("readiness", "UNSUPPORTED")
                .containsEntry("reason", "Found in poetry.lock, which Aegis doesn't edit; upgrade it by hand");
        assertThat(byName.get(mixedLib)).containsEntry("readiness", "READY");
        mvc.perform(post("/api/actions/risk-tickets").with(as("ANALYST", UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(response(byName.get(gradleLib).get("changeSignalId"), service)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pullRequests").isEmpty())
                .andExpect(jsonPath("$.evidence").value(hasItem("Not automated: " + gradleLib + " 1.0.0 is found in "
                        + "app/gradle.lockfile, which Aegis doesn't edit; upgrade it by hand")));
        mvc.perform(post("/api/actions/risk-tickets").with(as("ANALYST", UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(response(byName.get(mixedLib).get("changeSignalId"), service)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pullRequests[0].edits[*].manifestPath", contains("svc/pom.xml")))
                .andExpect(jsonPath("$.evidence").value(hasItem("Not automated: " + mixedLib + " 1.0.0 is also found in "
                        + "app/gradle.lockfile, which Aegis doesn't edit; upgrade it there by hand")));
    }

    /**
     * An npm package another one pulls in is overridden in the project of each lockfile resolving what pulls it in; one
     * a lockfile resolves for no declared package is overridden there too. Without a recorded lockfile, Aegis can't tell
     * where, and says to re-import.
     */
    @Test
    void anNpmPackageAnotherPullsInIsOverriddenWhereItsLockfileSays() throws Exception {
        String tag = Long.toHexString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        BusinessService service = inventory.createBusinessService(
                "Lockfiles " + tag, BusinessCriticality.CRITICAL, ServiceExposure.INTERNET_FACING);
        inventory.linkServiceRepository(service.getId(),
                RepositoryUrl.parse("https://gitlab.com/acme/lockfiles-" + tag, List.of("gitlab.com")), null);
        SoftwareComponent shadcn = inventory.createSoftwareComponent("npm", "shadcn-" + tag, "4.0.5");
        SoftwareComponent hono = inventory.createSoftwareComponent("npm", "hono-" + tag, "4.12.7");
        SoftwareComponent old = inventory.createSoftwareComponent("npm", "old-" + tag, "1.0.0");
        SoftwareComponent oldDep = inventory.createSoftwareComponent("npm", "old-dep-" + tag, "1.0.0");
        SoftwareComponent stray = inventory.createSoftwareComponent("npm", "stray-" + tag, "1.0.0");
        inventory.addComponentDependency(shadcn.getId(), hono.getId());
        inventory.addComponentDependency(old.getId(), oldDep.getId());
        store.linkRepositoryComponent(service.getId(), shadcn.getId(), List.of("client/package.json"),
                List.of("client/pnpm-lock.yaml"));
        store.linkRepositoryComponent(service.getId(), old.getId(), List.of("legacy/package.json"));
        store.linkRepositoryComponent(service.getId(), stray.getId(), List.of(), List.of("web/yarn.lock"));
        advisory("CVE-2099-" + tag + "1", "9.1", AffectedProduct.ofPackage("npm", "hono-" + tag, null, "4.0.0", null, null, "4.13.7"));
        advisory("CVE-2099-" + tag + "2", "8.1", AffectedProduct.ofPackage("npm", "old-dep-" + tag, null, "1.0.0", null, null, "1.1.0"));
        advisory("CVE-2099-" + tag + "3", "7.1", AffectedProduct.ofPackage("npm", "stray-" + tag, null, "1.0.0", null, null, "1.1.0"));

        Map<String, Map<String, Object>> byName = recommendations(service.getId(), as("ANALYST", UUID.randomUUID()))
                .stream().collect(Collectors.toMap(item -> JsonPath.read(item, "$.component.name"), item -> item));

        assertThat(byName.get("hono-" + tag)).containsEntry("readiness", "READY").containsEntry("toVersion", "4.13.7")
                .containsEntry("overrideIn", List.of("client/pnpm-workspace.yaml"));
        assertThat(byName.get("stray-" + tag)).containsEntry("readiness", "READY")
                .containsEntry("overrideIn", List.of("web/package.json"));
        assertThat(JsonPath.<Object>read(byName.get("stray-" + tag), "$.via")).isNull();
        assertThat(byName.get("old-dep-" + tag)).containsEntry("readiness", "TRANSITIVE")
                .containsEntry("reason", "old-dep-" + tag + " comes in through old-" + tag + "; re-import the service's "
                        + "SBOM from its repository so Aegis can tell which lockfile resolves it and override it there, or "
                        + "upgrade old-" + tag + " or pin old-dep-" + tag + " to 1.1.0 by hand");
    }

    /**
     * Each vulnerable dependency says where the service's code imports it (E11-3), from the code scan of the last import
     * from the repository: imported, with each line linked at the commit read; not imported; or unknown, with why. One
     * another package pulls in also says where the code imports what pulls it in.
     */
    @Test
    void eachVulnerableDependencySaysWhereTheServicesCodeImportsIt() throws Exception {
        String tag = Long.toHexString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        String revision = "0123456789abcdef0123456789abcdef01234567";
        BusinessService service = inventory.createBusinessService(
                "Code " + tag, BusinessCriticality.CRITICAL, ServiceExposure.INTERNET_FACING);
        inventory.linkServiceRepository(service.getId(),
                RepositoryUrl.parse("https://gitlab.com/acme/code-" + tag, List.of("gitlab.com")), null);
        SoftwareComponent used = inventory.createSoftwareComponent("npm", "used-" + tag, "1.0.0");
        SoftwareComponent unused = inventory.createSoftwareComponent("npm", "unused-" + tag, "1.0.0");
        SoftwareComponent byHand = inventory.createSoftwareComponent("npm", "hand-" + tag, "1.0.0");
        SoftwareComponent deep = inventory.createSoftwareComponent("npm", "deep-" + tag, "1.0.0");
        store.linkRepositoryComponent(service.getId(), used.getId(), List.of("package.json"), List.of("package-lock.json"));
        store.linkRepositoryComponent(service.getId(), unused.getId(), List.of("package.json"), List.of("package-lock.json"));
        inventory.addServiceComponent(service.getId(), byHand.getId());
        inventory.addComponentDependency(used.getId(), deep.getId());
        List<SoftwareComponent> vulnerable = List.of(used, unused, byHand, deep);
        for (int index = 0; index < vulnerable.size(); index++) {
            advisory("CVE-2099-" + tag + index, "8.1", AffectedProduct.ofPackage("npm", vulnerable.get(index).getName(), null,
                    "1.0.0", null, null, "1.1.0"));
        }
        // What the import from the repository found the code importing.
        BusinessService scanned = inventory.getBusinessService(service.getId());
        scanned.recordSbomImport(SbomImportSource.PUBLIC, Instant.now());
        scanned.recordImportScan(new ImportScan(revision, List.of("npm"), 4, null,
                List.of()), Instant.now());
        store.save(scanned);
        store.replaceServiceImports(service.getId(), List.of(new ImportScan.ModuleImports(
                "npm", "used-" + tag, 2, List.of(new ImportScan.Site("src/app.ts", 3),
                        new ImportScan.Site("src/b.ts", 9)))));

        Map<String, Map<String, Object>> byName = recommendations(service.getId(), as("ANALYST", UUID.randomUUID()))
                .stream().collect(Collectors.toMap(item -> JsonPath.read(item, "$.component.name"), item -> item));

        Map<String, Object> usedItem = byName.get("used-" + tag);
        assertThat(JsonPath.<String>read(usedItem, "$.usage.status")).isEqualTo("IMPORTED");
        assertThat(JsonPath.<Integer>read(usedItem, "$.usage.siteCount")).isEqualTo(2);
        assertThat(JsonPath.<String>read(usedItem, "$.usage.sites[0].url"))
                .isEqualTo("https://gitlab.com/acme/code-" + tag + "/-/blob/" + revision + "/src/app.ts#L3");
        assertThat(JsonPath.<Object>read(usedItem, "$.viaUsage")).isNull();
        assertThat(JsonPath.<String>read(byName.get("unused-" + tag), "$.usage.status")).isEqualTo("NOT_IMPORTED");
        assertThat(JsonPath.<String>read(byName.get("hand-" + tag), "$.usage.status")).isEqualTo("UNKNOWN");
        assertThat(JsonPath.<String>read(byName.get("hand-" + tag), "$.usage.reason"))
                .startsWith("It was linked by hand or by an uploaded SBOM");
        Map<String, Object> deepItem = byName.get("deep-" + tag);
        assertThat(JsonPath.<String>read(deepItem, "$.usage.status")).isEqualTo("NOT_IMPORTED");
        assertThat(JsonPath.<String>read(deepItem, "$.viaUsage.status")).isEqualTo("IMPORTED");

        // The score counts whether the code reaches each (E11-4): only the one nothing reaches is discounted, so it
        // ranks last — what the code imports, or might, keeps the full 8.1 x 1 x 1 = 81.0.
        assertThat(JsonPath.<String>read(usedItem, "$.reachability.status")).isEqualTo("IMPORTED");
        assertThat(JsonPath.<Double>read(usedItem, "$.score")).isEqualTo(81.0);
        assertThat(JsonPath.<String>read(byName.get("unused-" + tag), "$.reachability.status")).isEqualTo("NOT_IMPORTED");
        assertThat(JsonPath.<Double>read(byName.get("unused-" + tag), "$.score")).isEqualTo(64.8);
        assertThat(JsonPath.<String>read(byName.get("hand-" + tag), "$.reachability.status")).isEqualTo("UNKNOWN");
        assertThat(JsonPath.<Double>read(byName.get("hand-" + tag), "$.score")).isEqualTo(81.0);
        assertThat(JsonPath.<String>read(deepItem, "$.reachability.status")).isEqualTo("UNKNOWN");
        assertThat(JsonPath.<String>read(deepItem, "$.reachability.through")).isEqualTo("used-" + tag);
        assertThat(JsonPath.<String>read(deepItem, "$.reachability.reason")).isEqualTo("deep-" + tag
                + " isn't imported itself, but it comes in through used-" + tag + ", which the code imports");
        assertThat(JsonPath.<Double>read(deepItem, "$.score")).isEqualTo(81.0);
        assertThat(recommendations(service.getId(), as("ANALYST", UUID.randomUUID())).getLast())
                .containsEntry("score", 64.8);
    }

    /** A repository on a host no pull-request provider serves gets a ticket saying so, not a PR that fails later. */
    @Test
    void aRepositoryNoPullRequestProviderServesGetsATicketSayingSo() throws Exception {
        String tag = Long.toHexString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        String lib = "com.acme:bb-lib-" + tag;
        BusinessService service = inventory.createBusinessService(
                "Bitbucket " + tag, BusinessCriticality.HIGH, ServiceExposure.INTERNAL);
        inventory.linkServiceRepository(service.getId(),
                RepositoryUrl.parse("https://bitbucket.org/acme/bb-" + tag, List.of("bitbucket.org")), null);
        store.linkRepositoryComponent(service.getId(),
                inventory.createSoftwareComponent("maven", lib, "1.0.0").getId(), List.of("pom.xml"));
        advisory("CVE-2099-" + tag + "1", "9.1", AffectedProduct.ofPackage("Maven", lib, null, "1.0.0", null, null, "1.2.0"));

        Map<String, Object> item = recommendations(service.getId(), as("ANALYST", UUID.randomUUID())).getFirst();

        String unsupported = "No pull-request provider is enabled for bitbucket.org, so Aegis can't open the pull request";
        assertThat(item).containsEntry("readiness", "UNSUPPORTED").containsEntry("toVersion", "1.2.0")
                .containsEntry("reason", unsupported + "; upgrade it by hand");
        mvc.perform(post("/api/actions/risk-tickets").with(as("ANALYST", UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content(response(item.get("changeSignalId"), service)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pullRequests").isEmpty())
                .andExpect(jsonPath("$.evidence").value(hasItem("Not automated: " + unsupported + "; upgrade " + lib
                        + " by hand")));
    }

    private static String response(Object changeSignalId, BusinessService service) {
        return "{\"changeSignalId\":\"%s\",\"serviceId\":\"%s\"}".formatted(changeSignalId, service.getId());
    }

    private static String response(Object changeSignalId, Estate estate) {
        return "{\"changeSignalId\":\"%s\",\"serviceId\":\"%s\"}".formatted(changeSignalId, estate.service().getId());
    }

    /**
     * The list works each target out in memory from the advisories its search loaded; drafting looks them up
     * one vulnerability at a time. Both must pick the same version — across feeds, across every advisory
     * naming the package, and past a most-severe vulnerability that publishes no fix.
     */
    @Test
    void recommendationsPickTheVersionDraftingWould() throws Exception {
        String tag = Long.toHexString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        String artifact = "eq-lib-" + tag;
        String lib = "com.acme:" + artifact;
        String stuck = "com.acme:eq-stuck-" + tag;
        BusinessService service = inventory.createBusinessService(
                "Equivalent " + tag, BusinessCriticality.HIGH, ServiceExposure.INTERNAL);
        inventory.linkServiceRepository(service.getId(),
                RepositoryUrl.parse("https://gitlab.com/acme/eq-" + tag, List.of("gitlab.com")), null);
        SoftwareComponent libComponent = inventory.createSoftwareComponent("maven", lib, "1.0.0");
        SoftwareComponent stuckComponent = inventory.createSoftwareComponent("maven", stuck, "1.0.0");
        inventory.addServiceComponent(service.getId(), libComponent.getId());
        inventory.addServiceComponent(service.getId(), stuckComponent.getId());

        // One CVE from two feeds — NVD's CPE and OSV's purl — fixed in 1.2.0; a later one fixed only in 1.3.0.
        String remediated = "CVE-2099-" + tag + "1";
        advisories.upsert(new Advisory(null, "nvd", remediated, "A flaw.", Instant.now(), Instant.now(),
                new Advisory.Cvss("3.1", null, new BigDecimal("9.8"), "CRITICAL"),
                List.of(new AffectedProduct("cpe:2.3:a:acme:" + artifact + ":*:*:*:*:*:*:*:*", "a", "acme", artifact,
                        null, "1.0.0", null, null, "1.2.0"))));
        advisory(remediated, "9.8", AffectedProduct.ofPackage("Maven", lib, null, "1.0.0", null, null, "1.2.0"));
        advisory("CVE-2099-" + tag + "2", "5.0", AffectedProduct.ofPackage("Maven", lib, null, "1.1.0", null, null, "1.3.0"));
        // The most severe vulnerability of the other library publishes no fix; a milder one does.
        advisory("CVE-2099-" + tag + "3", "9.0", AffectedProduct.ofPackage("Maven", stuck, null, "1.0.0", null, "1.9.9", null));
        advisory("CVE-2099-" + tag + "4", "4.0", AffectedProduct.ofPackage("Maven", stuck, null, "1.0.0", null, null, "1.1.0"));

        List<Map<String, Object>> items = recommendations(service.getId(), as("ANALYST", UUID.randomUUID()));

        assertThat(items).hasSize(2).allSatisfy(item -> {
            ComponentRef component = new ComponentRef(UUID.fromString(JsonPath.read(item, "$.component.id")),
                    "maven", JsonPath.read(item, "$.component.name"), "1.0.0");
            String drafted = targets.resolve(UUID.fromString((String) item.get("changeSignalId")), component)
                    .toVersion();
            assertThat(item.get("toVersion")).as(component.name()).isEqualTo(drafted);
        });
        Map<String, Object> libItem = items.stream()
                .filter(item -> lib.equals(JsonPath.read(item, "$.component.name"))).findFirst().orElseThrow();
        assertThat(libItem).containsEntry("toVersion", "1.3.0");
        Map<String, Object> stuckItem = items.stream()
                .filter(item -> stuck.equals(JsonPath.read(item, "$.component.name"))).findFirst().orElseThrow();
        assertThat(stuckItem).containsEntry("toVersion", "1.1.0").containsEntry("readiness", "READY");
        assertThat(JsonPath.<String>read(stuckItem, "$.vulnerabilities[0].id")).isEqualTo("CVE-2099-" + tag + "3");
        assertThatThrownBy(() -> targets.resolve(
                UUID.fromString(JsonPath.read(stuckItem, "$.vulnerabilities[0].changeSignalId")),
                new ComponentRef(stuckComponent.getId(), "maven", stuck, "1.0.0")))
                .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("publishes no fixed version");
    }

    @Test
    void anotherOrganizationSeesNoneOfIt() throws Exception {
        Estate estate = estate();
        currentOrg.runAs(UUID.randomUUID(), () -> assertThatCode(() -> {
            assertThat(recommendations(null, as("ANALYST", UUID.randomUUID())))
                    .noneMatch(item -> item.get("serviceId").equals(estate.service().getId().toString()));
            mvc.perform(get(PATH).param("serviceId", estate.service().getId().toString())
                    .with(as("ANALYST", UUID.randomUUID()))).andExpect(status().isNotFound());
        }).doesNotThrowAnyException());
    }

    // ---- fixture -----------------------------------------------------------------------------------

    /**
     * A critical, internet-facing service linked to its repository, using directly: a Maven library with a
     * fixable CVE (declared in svc/pom.xml) that pulls in a second vulnerable library; a Rust crate (an
     * ecosystem Aegis does not edit); and a library whose advisory publishes no fix.
     */
    private Estate estate() {
        String tag = Long.toHexString(Math.abs(UUID.randomUUID().getMostSignificantBits()));
        String lib = "com.acme:rec-lib-" + tag;
        String deep = "com.acme:rec-deep-" + tag;
        String crate = "rec-crate-" + tag;
        String stuck = "com.acme:rec-stuck-" + tag;
        BusinessService service = inventory.createBusinessService(
                "Recommend " + tag, BusinessCriticality.CRITICAL, ServiceExposure.INTERNET_FACING);
        inventory.linkServiceRepository(service.getId(),
                RepositoryUrl.parse("https://gitlab.com/acme/rec-" + tag, List.of("gitlab.com")), null);

        SoftwareComponent libComponent = inventory.createSoftwareComponent("maven", lib, "1.0.0");
        SoftwareComponent deepComponent = inventory.createSoftwareComponent("maven", deep, "2.0.0");
        SoftwareComponent crateComponent = inventory.createSoftwareComponent("cargo", crate, "0.1.0");
        SoftwareComponent stuckComponent = inventory.createSoftwareComponent("maven", stuck, "1.0.0");
        store.linkRepositoryComponent(service.getId(), libComponent.getId(), List.of("svc/pom.xml"));
        inventory.addComponentDependency(libComponent.getId(), deepComponent.getId());
        inventory.addServiceComponent(service.getId(), crateComponent.getId());
        inventory.addServiceComponent(service.getId(), stuckComponent.getId());

        Advisory advisory = advisory("CVE-2099-" + tag + "1", "9.8",
                AffectedProduct.ofPackage("Maven", lib, null, "1.0.0", null, null, "1.2.0"));
        advisory("CVE-2099-" + tag + "2", "7.5", AffectedProduct.ofPackage("Maven", deep, null, "2.0.0", null, null, "2.1.0"));
        advisory("CVE-2099-" + tag + "3", "5.0", AffectedProduct.ofPackage("crates.io", crate, null, "0.1.0", null, null, "0.2.0"));
        advisory("CVE-2099-" + tag + "4", "4.0", AffectedProduct.ofPackage("Maven", stuck, null, "1.0.0", null, "1.9.9", null));
        return new Estate(tag, service, lib, libComponent.getId(), deep, crate, stuck, advisory);
    }

    private Advisory advisory(String cve, String baseScore, AffectedProduct affected) {
        return advisories.upsert(new Advisory(null, "osv", "GHSA-" + cve, cve, "A flaw.", Instant.now(), Instant.now(),
                new Advisory.Cvss("3.1", null, new BigDecimal(baseScore), null), List.of(affected)));
    }

    private record Estate(String tag, BusinessService service, String lib, UUID libId, String deep, String crate,
                          String stuck, Advisory advisory) {
    }

    private List<Map<String, Object>> recommendations(UUID serviceId, RequestPostProcessor caller) throws Exception {
        MockHttpServletRequestBuilder request = get(PATH).with(caller);
        if (serviceId != null) {
            request.param("serviceId", serviceId.toString());
        }
        String json = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$");
    }

    private static RequestPostProcessor as(String role, UUID userId) {
        return jwt().jwt(token -> token.subject(userId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static RequestPostProcessor serviceOwner(UUID userId) {
        return as("SERVICE_OWNER", userId);
    }
}
