package com.aegis.core.actions.upgrade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    @Test
    void everyVulnerableDependencyIsListedWithItsFixMostImpactFirst() throws Exception {
        Estate estate = estate();

        List<Map<String, Object>> items = recommendations(estate.service().getId(), as("ANALYST", UUID.randomUUID()));

        assertThat(items).extracting(item -> JsonPath.<String>read(item, "$.component.name"))
                .containsExactly(estate.lib(), estate.deep(), estate.crate(), estate.stuck());
        Map<String, Object> lib = items.getFirst();
        assertThat(lib).containsEntry("readiness", "READY").containsEntry("toVersion", "1.2.0")
                .containsEntry("changeSignalId", estate.advisory().id().toString())
                .containsEntry("manifestPath", "svc/pom.xml").containsEntry("canDraft", true)
                .containsEntry("serviceName", estate.service().getName()).containsEntry("reason", null);
        assertThat(JsonPath.<String>read(lib, "$.vulnerabilities[0].id")).isEqualTo("CVE-2099-" + estate.tag() + "1");
        assertThat(JsonPath.<Double>read(lib, "$.score")).isPositive();

        Map<String, Object> deep = items.get(1);
        assertThat(deep).containsEntry("readiness", "TRANSITIVE").containsEntry("toVersion", "2.1.0");
        assertThat(JsonPath.<String>read(deep, "$.via.name")).isEqualTo(estate.lib());
        assertThat((String) deep.get("reason")).isEqualTo(estate.deep() + " comes in through " + estate.lib()
                + "; upgrade " + estate.lib() + ", or pin " + estate.deep() + " to 2.1.0 by hand");
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
    void draftingARecommendationUsesItsManifestAndTheRecommendationThenShowsTheDraft() throws Exception {
        Estate estate = estate();
        Map<String, Object> lib = recommendations(estate.service().getId(), as("ANALYST", UUID.randomUUID())).getFirst();

        String body = "{\"changeSignalId\":\"%s\",\"serviceId\":\"%s\",\"componentId\":\"%s\"}".formatted(
                lib.get("changeSignalId"), estate.service().getId(), estate.libId());
        String created = mvc.perform(post("/api/actions/upgrade-proposals").with(as("ANALYST", UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.manifestPath").value("svc/pom.xml"))
                .andExpect(jsonPath("$.toVersion").value("1.2.0"))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> after = recommendations(estate.service().getId(), as("ANALYST", UUID.randomUUID())).getFirst();
        assertThat(JsonPath.<String>read(after, "$.proposal.id")).isEqualTo(JsonPath.<String>read(created, "$.id"));
        assertThat(JsonPath.<String>read(after, "$.proposal.status")).isEqualTo("PROPOSED");
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
        store.linkServiceComponent(service.getId(), libComponent.getId(), "svc/pom.xml");
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
