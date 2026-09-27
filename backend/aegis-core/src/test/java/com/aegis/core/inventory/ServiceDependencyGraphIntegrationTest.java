package com.aegis.core.inventory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.RepositoryUrl;
import com.aegis.core.inventory.sbom.FakeGitAndSyft;
import com.aegis.core.matching.VulnerableDependencies;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Each service's dependency edges are its own SBOM's, end to end: two lockfiles that resolve the same library
 * version differently are each reported with their own resolution — by matching, the recommendations and "Used by"
 * alike — and a re-import from the repository drops a transitive version the lockfile moved away from.
 */
class ServiceDependencyGraphIntegrationTest extends AbstractInventoryIntegrationTest {

    @Autowired private InventoryService inventory;
    @Autowired private AdvisoryStore advisories;
    @Autowired private VulnerableDependencies vulnerableDependencies;
    @Autowired private FakeGitAndSyft git;

    @Test
    void twoServicesOnOneLibraryVersionAreEachReportedWithTheirOwnLockfilesResolution() throws Exception {
        String tag = "res" + UUID.randomUUID().toString().substring(0, 8);
        BusinessService stale = inventory.createBusinessService("Stale " + tag, BusinessCriticality.HIGH);
        BusinessService current = inventory.createBusinessService("Current " + tag, BusinessCriticality.HIGH);
        // Both use http 1.0.0; one lockfile resolved it to redirects 1.0.0, the other to 2.0.0.
        upload(stale, tag, "1.0.0");
        upload(current, tag, "2.0.0");
        Advisory advisory = redirectsBelow2(tag);

        mvc.perform(get("/api/matching/change-signals/{id}", advisory.id()).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.impactedServices[*].service.id", contains(stale.getId().toString())))
                .andExpect(jsonPath("$.impactedServices[0].evidence[0].componentPath[*].name",
                        contains(tag + "-redirects", tag + "-http")));

        assertThat(vulnerableDependencies.find(List.of(stale.getId(), current.getId())).dependencies())
                .singleElement().satisfies(dependency -> {
                    assertThat(dependency.serviceId()).isEqualTo(stale.getId());
                    assertThat(dependency.component().version()).isEqualTo("1.0.0");
                    assertThat(dependency.via().name()).isEqualTo(tag + "-http");
                });

        mvc.perform(get("/api/inventory/components/used-by").param("ecosystem", "npm").param("name", tag + "-redirects")
                        .with(role("SERVICE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uses[*].service.name", contains("Current " + tag, "Stale " + tag)))
                .andExpect(jsonPath("$.uses[*].component.version", contains("2.0.0", "1.0.0")))
                .andExpect(jsonPath("$.uses[*].through[0].name", contains(tag + "-http", tag + "-http")));
    }

    @Test
    void aReimportFromTheRepositoryDropsATransitiveVersionTheLockfileMovedAwayFrom() throws Exception {
        String name = "moved" + UUID.randomUUID().toString().substring(0, 8);
        BusinessService service = inventory.createBusinessService("Moved " + name, BusinessCriticality.HIGH);
        inventory.linkServiceRepository(service.getId(),
                RepositoryUrl.parse("https://github.com/acme/" + name, List.of("github.com")), null);
        Advisory advisory = redirectsBelow2(name);

        git.willScan(name, FakeGitAndSyft.httpWithRedirects(name, "1.0.0"));
        importFromRepository(service)
                .andExpect(jsonPath("$.dependenciesCreated").value(1))
                .andExpect(jsonPath("$.dependenciesRemoved").value(0));
        mvc.perform(get("/api/matching/change-signals/{id}", advisory.id()).with(role("ANALYST")))
                .andExpect(jsonPath("$.impactedServices[*].service.id", contains(service.getId().toString())));

        // `npm update` moved redirects to 2.0.0; http is still 1.0.0, so its link stays.
        git.willScan(name, FakeGitAndSyft.httpWithRedirects(name, "2.0.0"));
        importFromRepository(service)
                .andExpect(jsonPath("$.dependenciesCreated").value(1))
                .andExpect(jsonPath("$.dependenciesRemoved").value(1))
                .andExpect(jsonPath("$.serviceComponentsUnlinked").value(0));

        mvc.perform(get("/api/matching/change-signals/{id}", advisory.id()).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.impactedServices").isEmpty());
        assertThat(vulnerableDependencies.find(List.of(service.getId())).dependencies()).isEmpty();
        mvc.perform(get("/api/inventory/components/used-by").param("ecosystem", "npm").param("name", name + "-redirects")
                        .with(role("ANALYST")))
                .andExpect(jsonPath("$.uses[*].component.version", contains("2.0.0")));
    }

    private void upload(BusinessService service, String tag, String redirects) throws Exception {
        String sbom = """
                {"bomFormat":"CycloneDX","specVersion":"1.5",
                 "metadata":{"component":{"bom-ref":"root","type":"application","name":"app"}},
                 "components":[
                  {"bom-ref":"http","type":"library","name":"%1$s-http","version":"1.0.0",
                   "purl":"pkg:npm/%1$s-http@1.0.0"},
                  {"bom-ref":"redirects","type":"library","name":"%1$s-redirects","version":"%2$s",
                   "purl":"pkg:npm/%1$s-redirects@%2$s"}],
                 "dependencies":[{"ref":"root","dependsOn":["http"]},{"ref":"http","dependsOn":["redirects"]}]}
                """.formatted(tag, redirects);
        mvc.perform(post("/api/inventory/services/{id}/sbom", service.getId()).with(role("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON).content(sbom))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions importFromRepository(BusinessService service)
            throws Exception {
        return mvc.perform(post("/api/inventory/services/{id}/sbom/from-repository", service.getId())
                        .with(role("ANALYST")))
                .andExpect(status().isOk());
    }

    /** An advisory for {@code <tag>-redirects} from 1.0.0 up to (not including) 2.0.0. */
    private Advisory redirectsBelow2(String tag) {
        String cve = "CVE-2097-" + Math.abs(UUID.randomUUID().getMostSignificantBits());
        return advisories.upsert(new Advisory(null, "nvd", cve, "A flaw in " + tag + "-redirects", Instant.now(),
                Instant.now(), null,
                List.of(AffectedProduct.ofPackage("npm", tag + "-redirects", null, "1.0.0", null, null, "2.0.0"))));
    }

    private static RequestPostProcessor role(String name) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + name));
    }
}
