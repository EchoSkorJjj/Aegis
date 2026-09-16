package com.aegis.core.inventory.sbom;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.aegis.core.inventory.InventoryService;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SbomImportApiIntegrationTest extends AbstractInventoryIntegrationTest {

    private static final String PATH = "/api/inventory/services/{serviceId}/sbom";

    @Autowired
    private InventoryService inventory;

    @Autowired
    private InventoryStore store;

    @Test
    void importsIdempotentlyAndFeedsTransitiveImpact() throws Exception {
        UUID serviceId = newService();
        String sbom = fixture();

        mvc.perform(post(PATH, serviceId).with(role("ANALYST")).contentType(MediaType.APPLICATION_JSON).content(sbom))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value(serviceId.toString()))
                .andExpect(jsonPath("$.componentsCreated").value(4))
                .andExpect(jsonPath("$.componentsMatched").value(0))
                .andExpect(jsonPath("$.componentsSkipped").value(1))
                .andExpect(jsonPath("$.dependenciesCreated").value(2))
                .andExpect(jsonPath("$.serviceComponentsLinked").value(2));

        // Re-import with the CycloneDX media type: everything matches, nothing new is written.
        mvc.perform(post(PATH, serviceId).with(role("COMPLIANCE_ADMIN"))
                        .contentType(SbomImportController.CYCLONEDX_JSON).content(sbom))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.componentsCreated").value(0))
                .andExpect(jsonPath("$.componentsMatched").value(4))
                .andExpect(jsonPath("$.dependenciesCreated").value(0))
                .andExpect(jsonPath("$.serviceComponentsLinked").value(0));

        mvc.perform(get("/api/inventory/services/{id}/components", serviceId).with(role("SERVICE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name", containsInAnyOrder("fixture-web-framework", "fixture-logger")));
        assertThat(store.findSoftwareComponentByCoordinates("npm", "@fixture/http-client", "1.1.0")).isPresent();

        UUID redirects = store.findSoftwareComponentByCoordinates("npm", "fixture-redirects", "0.9.0")
                .orElseThrow().getId();
        mvc.perform(get("/api/inventory/components/{id}/impact", redirects).with(role("SERVICE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services.length()").value(1))
                .andExpect(jsonPath("$.services[0].service.id").value(serviceId.toString()))
                .andExpect(jsonPath("$.services[0].direct").value(false))
                .andExpect(jsonPath("$.services[0].viaComponent.name").value("fixture-web-framework"));
    }

    @Test
    void requiresTheInventoryWriteRole() throws Exception {
        UUID serviceId = newService();
        String sbom = fixture();

        mvc.perform(post(PATH, serviceId).contentType(MediaType.APPLICATION_JSON).content(sbom))
                .andExpect(status().isUnauthorized());
        for (String role : new String[]{"SERVICE_OWNER", "USER", "ADMIN"}) {
            mvc.perform(post(PATH, serviceId).with(role(role)).contentType(MediaType.APPLICATION_JSON).content(sbom))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    @Test
    void rejectsBadInputWithTheUniformErrorContract() throws Exception {
        UUID serviceId = newService();

        mvc.perform(post(PATH, serviceId).with(role("ANALYST")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bomFormat\":\"SPDX\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", startsWith("Expected a CycloneDX")));
        mvc.perform(post(PATH, serviceId).with(role("ANALYST")).contentType(MediaType.APPLICATION_JSON)
                        .content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request"));
        mvc.perform(post(PATH, UUID.randomUUID()).with(role("ANALYST")).contentType(MediaType.APPLICATION_JSON)
                        .content(fixture()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void aRejectedSbomWritesNothing() throws Exception {
        UUID serviceId = newService();
        String sbom = """
                {"bomFormat":"CycloneDX","components":[
                  {"bom-ref":"ok","name":"fixture-rollback-probe","version":"1.0.0","purl":"pkg:npm/fixture-rollback-probe@1.0.0"},
                  {"bom-ref":"bad","name":"broken","version":"1","purl":"not-a-purl"}]}
                """;

        mvc.perform(post(PATH, serviceId).with(role("ANALYST")).contentType(MediaType.APPLICATION_JSON).content(sbom))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", startsWith("Invalid package URL")));

        assertThat(store.findSoftwareComponentByCoordinates("npm", "fixture-rollback-probe", "1.0.0")).isEmpty();
        mvc.perform(get("/api/inventory/services/{id}/components", serviceId).with(role("ANALYST")))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void importedComponentsAreVisibleInTheComponentList() throws Exception {
        UUID serviceId = newService();
        mvc.perform(post(PATH, serviceId).with(role("ANALYST")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bomFormat\":\"CycloneDX\",\"components\":[{\"purl\":\"pkg:cargo/fixture-crate@0.3.1\"}]}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/inventory/services/{id}/components", serviceId).with(role("ANALYST")))
                .andExpect(jsonPath("$.content[*].ecosystem", hasItem("cargo")));
    }

    private UUID newService() {
        return inventory.createBusinessService("SBOM " + UUID.randomUUID(), BusinessCriticality.MEDIUM).getId();
    }

    private static String fixture() throws IOException {
        try (InputStream in = SbomImportApiIntegrationTest.class.getClassLoader()
                .getResourceAsStream(SbomImportServiceTest.FIXTURE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static RequestPostProcessor role(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
