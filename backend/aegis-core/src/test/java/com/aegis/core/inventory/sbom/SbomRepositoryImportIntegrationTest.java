package com.aegis.core.inventory.sbom;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.aegis.core.inventory.InventoryFilters;
import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.RepositoryUrl;
import com.aegis.core.inventory.domain.SbomImportSource;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.platform.tenant.CurrentOrg;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import com.aegis.core.support.TestOwners;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Importing a service's SBOM from its linked repository, end to end through the API: the repository is the
 * service's own link, never a caller's input, and access is a pasted token, else a host integration (a
 * stand-in for the GitHub App here), else none. git and syft are {@link FakeGitAndSyft}.
 */
class SbomRepositoryImportIntegrationTest extends AbstractInventoryIntegrationTest {

    private static final String IMPORT = "/api/inventory/services/{serviceId}/sbom/from-repository";

    @Autowired private InventoryService inventory;
    @Autowired private InventoryStore store;
    @Autowired private FakeGitAndSyft git;
    @Autowired private TestOwners owners;
    @Autowired private CurrentOrg currentOrg;

    @Test
    void importsTheLinkedRepositoryAtItsBranchWithoutCredentials() throws Exception {
        String name = "widgets-" + UUID.randomUUID();
        BusinessService service = linked("https://github.com/acme/" + name, "develop");

        mvc.perform(post(IMPORT, service.getId()).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.componentsCreated").value(1))
                .andExpect(jsonPath("$.serviceComponentsLinked").value(1));

        FakeGitAndSyft.Clone clone = git.lastCloneOf(name);
        assertThat(clone.url()).isEqualTo("https://github.com/acme/" + name + ".git");
        assertThat(clone.command()).containsSequence("--branch", "develop");
        assertThat(clone.env()).doesNotContainKeys("GIT_ASKPASS", "AEGIS_GIT_TOKEN");
        assertThat(inventory.listServiceComponents(service.getId(), Pageable.unpaged()).getContent())
                .extracting(component -> component.getName()).containsExactly(name + "-lib");
        mvc.perform(get("/api/inventory/services/{id}", service.getId()).with(role("SERVICE_OWNER")))
                .andExpect(jsonPath("$.sbomImportSource").value("PUBLIC"))
                .andExpect(jsonPath("$.sbomImportedAt").value(notNullValue()));
    }

    @Test
    void aPrivateRepositoryNeedsAPastedTokenWhichIsUsedOnceAndNeverEchoed() throws Exception {
        String name = "private-" + UUID.randomUUID();
        BusinessService service = linked("https://gitlab.com/group/sub/" + name, null);

        mvc.perform(post(IMPORT, service.getId()).with(role("ANALYST")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("https://gitlab.com/group/sub/" + name
                        + " is private or doesn't exist. Add a read-only access token to import it"));

        String token = "glpat-" + UUID.randomUUID();
        mvc.perform(importWith(service.getId(), token, null).with(role("COMPLIANCE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(token))));
        FakeGitAndSyft.Clone clone = git.lastCloneOf(name);
        assertThat(clone.url()).isEqualTo("https://oauth2@gitlab.com/group/sub/" + name + ".git");
        assertThat(clone.env()).containsEntry("AEGIS_GIT_TOKEN", token);
        assertThat(clone.command()).noneMatch(argument -> argument.contains(token));
        assertThat(inventory.getBusinessService(service.getId()).getSbomImportSource()).isEqualTo(SbomImportSource.TOKEN);

        mvc.perform(importWith(service.getId(), "wrong-token", null).with(role("ANALYST")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("The access token was refused")));
    }

    @Test
    void aGitHubRepositoryTheAppCoversNeedsNoTokenAndAPastedOneStillWins() throws Exception {
        String name = "private-" + UUID.randomUUID();
        BusinessService service = linked("https://github.com/app-installed/" + name, null);

        mvc.perform(post(IMPORT, service.getId()).with(role("ANALYST"))).andExpect(status().isOk());
        FakeGitAndSyft.Clone viaApp = git.lastCloneOf(name);
        assertThat(viaApp.url()).isEqualTo("https://x-access-token@github.com/app-installed/" + name + ".git");
        assertThat(viaApp.env()).containsEntry("AEGIS_GIT_TOKEN", SbomTestConfig.APP_TOKEN);
        assertThat(inventory.getBusinessService(service.getId()).getSbomImportSource())
                .isEqualTo(SbomImportSource.INTEGRATION);

        mvc.perform(importWith(service.getId(), "ghp_mine", "jdoe").with(role("ANALYST"))).andExpect(status().isOk());
        FakeGitAndSyft.Clone viaToken = git.lastCloneOf(name);
        assertThat(viaToken.url()).isEqualTo("https://jdoe@github.com/app-installed/" + name + ".git");
        assertThat(viaToken.env()).containsEntry("AEGIS_GIT_TOKEN", "ghp_mine");
    }

    @Test
    void whenTheAppCannotHelpTheRefusalSaysWhy() throws Exception {
        String owner = "elsewhere-" + UUID.randomUUID();
        BusinessService service = linked("https://github.com/" + owner + "/private-repo", null);

        mvc.perform(post(IMPORT, service.getId()).with(role("ANALYST")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("https://github.com/" + owner + "/private-repo is private or "
                        + "doesn't exist (the Aegis GitHub App is not installed on " + owner + "). "
                        + "Add a read-only access token to import it"));
    }

    @Test
    void theRecordedImportDescribesTheLinkedRepositoryAndAnUploadIsRecordedToo() throws Exception {
        String name = "status-" + UUID.randomUUID();
        BusinessService service = linked("https://github.com/acme/" + name, "main");
        mvc.perform(post(IMPORT, service.getId()).with(role("ANALYST"))).andExpect(status().isOk());

        // Saving the same link keeps the record; another branch or repository forgets it.
        inventory.linkServiceRepository(service.getId(), github("https://github.com/acme/" + name), "main");
        assertThat(inventory.getBusinessService(service.getId()).getSbomImportSource()).isEqualTo(SbomImportSource.PUBLIC);
        inventory.linkServiceRepository(service.getId(), github("https://github.com/acme/" + name), "develop");
        assertThat(inventory.getBusinessService(service.getId()).getSbomImportSource()).isNull();
        assertThat(inventory.getBusinessService(service.getId()).getSbomImportedAt()).isNull();

        String upload = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\",\"components\":[{\"type\":\"library\","
                + "\"name\":\"" + name + "-ci\",\"version\":\"1.0.0\",\"purl\":\"pkg:npm/" + name + "-ci@1.0.0\"}]}";
        mvc.perform(post("/api/inventory/services/{id}/sbom", service.getId()).with(role("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON).content(upload))
                .andExpect(status().isOk());
        mvc.perform(get("/api/inventory/services/{id}", service.getId()).with(role("ANALYST")))
                .andExpect(jsonPath("$.sbomImportSource").value("UPLOAD"));

        inventory.unlinkServiceRepository(service.getId());
        assertThat(inventory.getBusinessService(service.getId()).getSbomImportSource()).isNull();
    }

    @Test
    void aRepositorysOwnPackagesAreTheRepositoryAndItsLibrariesAreDirectWithTheirManifests() throws Exception {
        String name = "monorepo-" + UUID.randomUUID();
        BusinessService service = linked("https://github.com/acme/" + name, null);
        // What an import before this fix left behind: the npm project linked as if it were a library.
        SoftwareComponent stale = inventory.createSoftwareComponent("npm", name + "-web", "0.0.0");
        inventory.addServiceComponent(service.getId(), stale.getId());

        mvc.perform(post(IMPORT, service.getId()).with(role("ANALYST"))).andExpect(status().isOk());

        Map<String, String> direct = store.findServiceComponentLinksForServices(List.of(service.getId())).stream()
                .collect(Collectors.toMap(link -> inventory.getSoftwareComponent(link.componentId()).getName(),
                        link -> String.join(",", link.manifestPaths())));
        assertThat(direct).containsOnly(
                Map.entry(name + "-http", "web/package.json"),
                Map.entry("com.acme:" + name + "-core", "svc/pom.xml"));
        // Below the direct ones nothing changes, and a version syft could not resolve is not a component.
        assertThat(inventory.listSoftwareComponents(new InventoryFilters.ComponentFilter(name, null, null, null, null),
                Pageable.unpaged()).getContent()).extracting(SoftwareComponent::getName)
                .contains(name + "-util").doesNotContain("com.acme:" + name + "-managed", "com.acme:" + name + "-svc");
    }

    @Test
    void theSameVersionDeclaredInTwoManifestsIsLinkedWithBoth() throws Exception {
        String name = "twolocks-" + UUID.randomUUID();
        BusinessService service = linked("https://github.com/acme/" + name, null);

        mvc.perform(post(IMPORT, service.getId()).with(role("ANALYST"))).andExpect(status().isOk());

        assertThat(store.findServiceComponentLinksForServices(List.of(service.getId()))).singleElement()
                .satisfies(link -> {
                    assertThat(inventory.getSoftwareComponent(link.componentId()).getName()).isEqualTo(name + "-http");
                    assertThat(link.manifestPaths()).containsExactly("client/package.json", "server/package.json");
                });
    }

    @Test
    void aReimportUnlinksWhatTheRepositoryNoLongerDeclaresAndKeepsWhatPeopleLinked() throws Exception {
        String name = "upgraded-" + UUID.randomUUID();
        BusinessService service = linked("https://github.com/acme/" + name, null);
        git.willScan(name, FakeGitAndSyft.npmProjects(name, Map.of("client", "1.5.0", "server", "1.5.1")));
        mvc.perform(post(IMPORT, service.getId()).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceComponentsLinked").value(2))
                .andExpect(jsonPath("$.serviceComponentsUnlinked").value(0));
        // Linked by hand, and by an uploaded SBOM, which only adds: neither is the repository's to unlink.
        SoftwareComponent byHand = inventory.createSoftwareComponent("npm", name + "-vendored", "1.0.0");
        inventory.addServiceComponent(service.getId(), byHand.getId());
        String upload = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\",\"components\":[{\"type\":\"library\","
                + "\"name\":\"" + name + "-ci\",\"version\":\"1.0.0\",\"purl\":\"pkg:npm/" + name + "-ci@1.0.0\"}]}";
        mvc.perform(post("/api/inventory/services/{id}/sbom", service.getId()).with(role("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON).content(upload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceComponentsUnlinked").value(0));
        assertThat(linksOf(service)).hasSize(4);

        // The upgrade merged and both lockfiles were refreshed, so they pin the fixed version.
        git.willScan(name, FakeGitAndSyft.npmProjects(name, Map.of("client", "1.18.0", "server", "1.18.0")));
        mvc.perform(post(IMPORT, service.getId()).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceComponentsLinked").value(1))
                .andExpect(jsonPath("$.serviceComponentsUnlinked").value(2));

        assertThat(linksOf(service)).containsOnly(
                Map.entry(name + "-http@1.18.0", "client/package.json,server/package.json"),
                Map.entry(name + "-vendored@1.0.0", ""),
                Map.entry(name + "-ci@1.0.0", ""));
        // The old versions stay in the inventory; they are just no longer this service's.
        assertThat(store.findSoftwareComponentByCoordinates("npm", name + "-http", "1.5.0")).isPresent();
    }

    @Test
    void thereIsNothingToImportFromWithoutALinkedRepository() throws Exception {
        BusinessService service = inventory.createBusinessService("Unlinked " + UUID.randomUUID(), BusinessCriticality.LOW);

        mvc.perform(post(IMPORT, service.getId()).with(role("ANALYST")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Link the service's repository before importing its SBOM from it"));
        mvc.perform(importWith(service.getId(), "t".repeat(501), null).with(role("ANALYST")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void governanceAndTheServicesOwnersImportAndNobodyElseCan() throws Exception {
        String name = "owned-" + UUID.randomUUID();
        BusinessService service = linked("https://bitbucket.org/team/" + name, null);
        Owner owner = owners.assign(service.getId(), "Import Owner " + UUID.randomUUID());

        mvc.perform(post(IMPORT, service.getId()).with(serviceOwner(owner.getUserId()))).andExpect(status().isOk());
        for (RequestPostProcessor caller : List.of(serviceOwner(UUID.randomUUID()), role("USER"), role("ORG_ADMIN"),
                role("PLATFORM_ADMIN"))) {
            mvc.perform(post(IMPORT, service.getId()).with(caller)).andExpect(status().isForbidden());
        }
        mvc.perform(post(IMPORT, service.getId())).andExpect(status().isUnauthorized());
        currentOrg.runAs(UUID.randomUUID(), () -> assertThatCode(() ->
                mvc.perform(post(IMPORT, service.getId()).with(role("ANALYST"))).andExpect(status().isNotFound()))
                .doesNotThrowAnyException());
    }

    private BusinessService linked(String url, String branch) {
        BusinessService service = inventory.createBusinessService("Imported " + UUID.randomUUID(), BusinessCriticality.HIGH);
        return inventory.linkServiceRepository(service.getId(), github(url), branch);
    }

    /** The service's direct dependencies, {@code name@version} → the manifests declaring it. */
    private Map<String, String> linksOf(BusinessService service) {
        return store.findServiceComponentLinksForServices(List.of(service.getId())).stream()
                .collect(Collectors.toMap(link -> {
                    SoftwareComponent component = inventory.getSoftwareComponent(link.componentId());
                    return component.getName() + "@" + component.getVersion();
                }, link -> String.join(",", link.manifestPaths())));
    }

    private static RepositoryUrl github(String url) {
        return RepositoryUrl.parse(url, List.of("github.com", "gitlab.com", "bitbucket.org"));
    }

    private static MockHttpServletRequestBuilder importWith(UUID serviceId, String token, String username) {
        String body = "{\"token\":\"" + token + "\"" + (username == null ? "" : ",\"username\":\"" + username + "\"") + "}";
        return post(IMPORT, serviceId).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static RequestPostProcessor role(String name) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + name));
    }

    private static RequestPostProcessor serviceOwner(UUID id) {
        return jwt().jwt(token -> token.subject(id.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE_OWNER"));
    }
}
