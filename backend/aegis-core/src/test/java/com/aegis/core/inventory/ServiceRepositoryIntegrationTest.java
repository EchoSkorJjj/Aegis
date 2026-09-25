package com.aegis.core.inventory;

import java.util.List;
import java.util.UUID;

import com.aegis.core.actions.pr.PrProviders;
import com.aegis.core.actions.pr.RepositoryRef;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.platform.tenant.CurrentOrg;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import com.aegis.core.support.TestOwners;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Linking a repository must remain available when the external SBOM generation tooling is disabled.
@SpringBootTest(properties = "aegis.inventory.sbom-generation.enabled=false")
class ServiceRepositoryIntegrationTest extends AbstractInventoryIntegrationTest {

    private static final String REPOSITORY = "/api/inventory/services/{serviceId}/repository";
    private static final String URL = "https://github.com/acme/payments";

    @Autowired private InventoryService inventory;
    @Autowired private TestOwners owners;
    @Autowired private CurrentOrg currentOrg;
    @Autowired private PrProviders providers;

    @Test
    void analystRoundTripCanonicalizesPersistsEditsAndUnlinks() throws Exception {
        BusinessService service = service();
        mvc.perform(link(service.getId(), "https://GitHub.COM/acme/payments.git/", "develop").with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryUrl").value(URL))
                .andExpect(jsonPath("$.repositoryBranch").value("develop"));
        mvc.perform(get("/api/inventory/services/{id}", service.getId()).with(role("SERVICE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryUrl").value(URL))
                .andExpect(jsonPath("$.repositoryBranch").value("develop"));
        mvc.perform(link(service.getId(), "https://gitlab.com/group/sub/repo", null).with(role("ANALYST")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.repositoryBranch").value(nullValue()));
        mvc.perform(delete(REPOSITORY, service.getId()).with(role("ANALYST")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/inventory/services/{id}", service.getId()).with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryUrl").value(nullValue()))
                .andExpect(jsonPath("$.repositoryBranch").value(nullValue()));
    }

    @Test
    void complianceAndAssignedOwnerCanLinkAndUnlink() throws Exception {
        BusinessService service = service();
        Owner owner = owners.assign(service.getId(), "Repository Owner " + UUID.randomUUID());
        for (RequestPostProcessor caller : List.of(role("COMPLIANCE_ADMIN"), serviceOwner(owner.getUserId()))) {
            mvc.perform(link(service.getId(), URL, "develop").with(caller)).andExpect(status().isOk());
            mvc.perform(delete(REPOSITORY, service.getId()).with(caller)).andExpect(status().isNoContent());
        }
    }

    @Test
    void unauthorizedCallersCannotLinkOrUnlink() throws Exception {
        BusinessService service = service();
        owners.assign(service.getId(), "Actual Owner " + UUID.randomUUID());
        mvc.perform(link(service.getId(), URL, null).with(role("ANALYST"))).andExpect(status().isOk());
        for (RequestPostProcessor caller : List.of(serviceOwner(UUID.randomUUID()), role("USER"),
                role("ORG_ADMIN"), role("PLATFORM_ADMIN"))) {
            mvc.perform(link(service.getId(), URL, "other").with(caller)).andExpect(status().isForbidden());
            mvc.perform(delete(REPOSITORY, service.getId()).with(caller)).andExpect(status().isForbidden());
        }
        mvc.perform(link(service.getId(), URL, null)).andExpect(status().isUnauthorized());
        mvc.perform(delete(REPOSITORY, service.getId())).andExpect(status().isUnauthorized());
        assertThat(inventory.getBusinessService(service.getId()).getRepositoryUrl()).isEqualTo(URL);
    }

    @Test
    void invalidInputReturns400WithoutChangingTheLink() throws Exception {
        BusinessService service = service();
        mvc.perform(link(service.getId(), URL, "develop").with(role("ANALYST"))).andExpect(status().isOk());
        for (String bad : List.of("", "http://github.com/a/b", "https://evil.example/a/b", "https://github.com/a",
                "https://github.com/a/../b", "https://github.com/a/%2e%2e/b", "https://github.com/a/b?token=x",
                "https://github.com/a/" + "b".repeat(401))) {
            mvc.perform(link(service.getId(), bad, null).with(role("ANALYST")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        }
        for (String bad : List.of("-main", "a..b", "a".repeat(201))) {
            mvc.perform(link(service.getId(), URL, bad).with(role("ANALYST"))).andExpect(status().isBadRequest());
        }
        mvc.perform(put(REPOSITORY, service.getId()).with(role("ANALYST"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(inventory.getBusinessService(service.getId()).getRepositoryBranch()).isEqualTo("develop");
    }

    @Test
    void governanceUpdateKeepsRepositoryAndMissingServiceReturns404() throws Exception {
        BusinessService service = service();
        mvc.perform(link(service.getId(), URL, "develop").with(role("ANALYST"))).andExpect(status().isOk());
        mvc.perform(put("/api/inventory/services/{id}", service.getId()).with(role("ANALYST"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed " + UUID.randomUUID() + "\",\"criticality\":\"LOW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryUrl").value(URL))
                .andExpect(jsonPath("$.repositoryBranch").value("develop"));
        mvc.perform(link(UUID.randomUUID(), URL, null).with(role("ANALYST"))).andExpect(status().isNotFound());
        mvc.perform(delete(REPOSITORY, UUID.randomUUID()).with(role("ANALYST"))).andExpect(status().isNotFound());
    }

    @Test
    void anotherOrganizationCannotSeeOrChangeTheRepository() throws Exception {
        BusinessService service = service();
        Owner owner = owners.assign(service.getId(), "Tenant Owner " + UUID.randomUUID());
        mvc.perform(link(service.getId(), URL, "develop").with(role("ANALYST"))).andExpect(status().isOk());
        currentOrg.runAs(UUID.randomUUID(), () -> assertThatCode(() -> {
            mvc.perform(get("/api/inventory/services/{id}", service.getId()).with(role("ANALYST")))
                    .andExpect(status().isNotFound());
            mvc.perform(link(service.getId(), URL, "other").with(role("ANALYST")))
                    .andExpect(status().isNotFound());
            mvc.perform(delete(REPOSITORY, service.getId()).with(role("ANALYST")))
                    .andExpect(status().isNotFound());
            mvc.perform(link(service.getId(), URL, "other").with(serviceOwner(owner.getUserId())))
                    .andExpect(status().isForbidden());
            mvc.perform(delete(REPOSITORY, service.getId()).with(serviceOwner(owner.getUserId())))
                    .andExpect(status().isForbidden());
        }).doesNotThrowAnyException());
        assertThat(inventory.getBusinessService(service.getId()).getRepositoryBranch()).isEqualTo("develop");
    }

    @Test
    void applicationBootsWithNoPrProviderEnabled() {
        assertThatThrownBy(() -> providers.forRepository(new RepositoryRef("github.com", "acme/payments")))
                .isInstanceOf(ApiExceptions.Conflict.class).hasMessageContaining("github.com");
    }

    private BusinessService service() {
        return inventory.createBusinessService("Repository " + UUID.randomUUID(), BusinessCriticality.HIGH);
    }

    private static MockHttpServletRequestBuilder link(UUID id, String url, String branch) {
        return put(REPOSITORY, id).contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(new InventoryDtos.ServiceRepositoryRequest(url, branch)));
    }

    private static RequestPostProcessor role(String name) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + name));
    }

    private static RequestPostProcessor serviceOwner(UUID id) {
        return jwt().jwt(token -> token.subject(id.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE_OWNER"));
    }
}
