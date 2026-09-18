package com.aegis.core.inventory;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import com.aegis.core.support.AbstractInventoryIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InventoryApiIntegrationTest extends AbstractInventoryIntegrationTest {

    private static final Pattern ID_PATTERN = Pattern.compile("\\\"id\\\":\\\"([^\\\"]+)\\\"");

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @Test
    void mapsIdentityRolesClaimToSpringRoleAuthorities() {
        Jwt jwt = Jwt.withTokenValue("identity-service-access-token")
                .header("alg", "RS256")
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of(
                        "USER", "ADMIN", "SERVICE_OWNER", "ANALYST", "COMPLIANCE_ADMIN"))
                .build();

        assertThat(jwtAuthenticationConverter.convert(jwt).getAuthorities())
                .extracting(authority -> authority.getAuthority())
                // A JWT (resource-server) authentication is a "bearer" factor, so Spring Security 7
                // attaches FactorGrantedAuthority.BEARER_AUTHORITY ("FACTOR_BEARER") to the mapped roles.
                .containsExactlyInAnyOrder(
                        "ROLE_USER", "ROLE_ADMIN", "ROLE_SERVICE_OWNER", "ROLE_ANALYST",
                        "ROLE_COMPLIANCE_ADMIN", FactorGrantedAuthority.BEARER_AUTHORITY);
    }

    @Test
    void enforcesAuthenticationAndRoleBasedReadWriteAccessWithUniformErrors() throws Exception {
        mvc.perform(get("/api/inventory/services"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.path").value("/api/inventory/services"));

        mvc.perform(get("/api/inventory/services").with(role("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.path").value("/api/inventory/services"));

        // ADMIN manages platform identities; it is deliberately not an Aegis inventory role.
        mvc.perform(get("/api/inventory/services").with(role("ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        for (String platformRole : List.of("USER", "ADMIN")) {
            mvc.perform(post("/api/inventory/services")
                            .with(role(platformRole))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Forbidden " + platformRole + "\",\"criticality\":\"LOW\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.error").value("Forbidden"))
                    .andExpect(jsonPath("$.path").value("/api/inventory/services"));
        }

        for (String readerRole : List.of("SERVICE_OWNER", "ANALYST", "COMPLIANCE_ADMIN")) {
            mvc.perform(get("/api/inventory/services").with(role(readerRole)))
                    .andExpect(status().isOk());
        }

        mvc.perform(post("/api/inventory/services")
                        .with(role("SERVICE_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Forbidden service\",\"criticality\":\"LOW\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mvc.perform(post("/api/inventory/services")
                        .with(role("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Allowed " + UUID.randomUUID() + "\",\"criticality\":\"LOW\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/inventory/owners")
                        .with(role("COMPLIANCE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Allowed owner " + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void roundTripsServiceExposureAndDefaultsItWhenTheRequestOmitsIt() throws Exception {
        String suffix = UUID.randomUUID().toString();
        UUID declared = create("/api/inventory/services",
                "{\"name\":\"Public " + suffix + "\",\"criticality\":\"HIGH\","
                        + "\"exposure\":\"INTERNET_FACING\"}");
        UUID omitted = create("/api/inventory/services",
                "{\"name\":\"Unstated " + suffix + "\",\"criticality\":\"HIGH\"}");

        mvc.perform(get("/api/inventory/services/{id}", declared).with(role("SERVICE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exposure").value("INTERNET_FACING"));
        // A client written before the attribute existed still works, and lands on the safe middle.
        mvc.perform(get("/api/inventory/services/{id}", omitted).with(role("SERVICE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exposure").value("INTERNAL"));

        // A PUT that does not mention exposure must not downgrade a service the owner declared public.
        mvc.perform(put("/api/inventory/services/{id}", declared)
                        .with(role("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Public " + suffix + "\",\"criticality\":\"LOW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criticality").value("LOW"))
                .andExpect(jsonPath("$.exposure").value("INTERNET_FACING"));

        mvc.perform(put("/api/inventory/services/{id}", declared)
                        .with(role("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Public " + suffix + "\",\"criticality\":\"LOW\","
                                + "\"exposure\":\"ISOLATED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exposure").value("ISOLATED"));

        mvc.perform(post("/api/inventory/services")
                        .with(role("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bad " + suffix + "\",\"criticality\":\"LOW\","
                                + "\"exposure\":\"DMZ\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void businessServiceCrudReturnsRestfulStatusesAndLocations() throws Exception {
        String suffix = UUID.randomUUID().toString();
        UUID id = create("/api/inventory/services",
                "{\"name\":\"Payments " + suffix + "\",\"criticality\":\"CRITICAL\"}");

        mvc.perform(put("/api/inventory/services/{id}", id)
                        .with(role("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Checkout " + suffix + "\",\"criticality\":\"HIGH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Checkout " + suffix))
                .andExpect(jsonPath("$.criticality").value("HIGH"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        mvc.perform(get("/api/inventory/services/{id}", id).with(role("SERVICE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Checkout " + suffix));
        mvc.perform(get("/api/inventory/services?size=50").with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        mvc.perform(delete("/api/inventory/services/{id}", id).with(role("COMPLIANCE_ADMIN")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/inventory/services/{id}", id).with(role("ANALYST")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void identityAndTimestampsAreServerControlledInCreateAndUpdateDtos() throws Exception {
        UUID suppliedId = UUID.randomUUID();
        String suppliedCreatedAt = "2000-01-01T00:00:00Z";
        String suppliedUpdatedAt = "2001-01-01T00:00:00Z";
        String suffix = UUID.randomUUID().toString();

        MvcResult created = mvc.perform(post("/api/inventory/services")
                        .with(role("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + suppliedId + "\",\"name\":\"Controlled " + suffix
                                + "\",\"criticality\":\"LOW\",\"createdAt\":\"" + suppliedCreatedAt
                                + "\",\"updatedAt\":\"" + suppliedUpdatedAt + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(suppliedId.toString())))
                .andExpect(jsonPath("$.createdAt").value(org.hamcrest.Matchers.not(suppliedCreatedAt)))
                .andExpect(jsonPath("$.updatedAt").value(org.hamcrest.Matchers.not(suppliedUpdatedAt)))
                .andReturn();
        Matcher matcher = ID_PATTERN.matcher(created.getResponse().getContentAsString());
        assertThat(matcher.find()).isTrue();
        UUID actualId = UUID.fromString(matcher.group(1));

        mvc.perform(put("/api/inventory/services/{id}", actualId)
                        .with(role("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + suppliedId + "\",\"name\":\"Still controlled " + suffix
                                + "\",\"criticality\":\"HIGH\",\"createdAt\":\"" + suppliedCreatedAt
                                + "\",\"updatedAt\":\"" + suppliedUpdatedAt + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(actualId.toString()))
                .andExpect(jsonPath("$.createdAt").value(org.hamcrest.Matchers.not(suppliedCreatedAt)))
                .andExpect(jsonPath("$.updatedAt").value(org.hamcrest.Matchers.not(suppliedUpdatedAt)));
    }

    @Test
    void componentVendorAndOwnerCrudSuccessPaths() throws Exception {
        String suffix = UUID.randomUUID().toString();
        UUID componentId = create("/api/inventory/components",
                "{\"ecosystem\":\"Maven\",\"name\":\"core-" + suffix + "\",\"version\":\"1.0\"}");
        UUID vendorId = create("/api/inventory/vendors", "{\"name\":\"Vendor " + suffix + "\"}");
        UUID ownerId = create("/api/inventory/owners", "{\"name\":\"Owner " + suffix + "\"}");

        mvc.perform(put("/api/inventory/components/{id}", componentId).with(role("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ecosystem\":\"Maven\",\"name\":\"core-" + suffix
                                + "\",\"version\":\"2.0\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value("2.0"));
        mvc.perform(put("/api/inventory/vendors/{id}", vendorId).with(role("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed vendor " + suffix + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renamed vendor " + suffix));
        mvc.perform(put("/api/inventory/owners/{id}", ownerId).with(role("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed owner " + suffix + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renamed owner " + suffix));

        mvc.perform(get("/api/inventory/components/{id}", componentId).with(role("SERVICE_OWNER")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/inventory/vendors/{id}", vendorId).with(role("SERVICE_OWNER")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/inventory/owners/{id}", ownerId).with(role("SERVICE_OWNER")))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/inventory/components/{id}", componentId).with(role("ANALYST")))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/inventory/vendors/{id}", vendorId).with(role("ANALYST")))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/inventory/owners/{id}", ownerId).with(role("ANALYST")))
                .andExpect(status().isNoContent());
    }

    @Test
    void validatesRequestsAndReportsConflictsAndMissingRecords() throws Exception {
        mvc.perform(post("/api/inventory/services").with(role("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \",\"criticality\":\"LOW\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("name must not be blank"));

        mvc.perform(post("/api/inventory/services").with(role("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Broken\",\"criticality\":\"EXTREME\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request"));

        String duplicateName = "Duplicate " + UUID.randomUUID();
        create("/api/inventory/vendors", "{\"name\":\"" + duplicateName + "\"}");
        mvc.perform(post("/api/inventory/vendors").with(role("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + duplicateName.toUpperCase() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"));

        UUID missing = UUID.randomUUID();
        mvc.perform(get("/api/inventory/owners/{id}", missing).with(role("ANALYST")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.path").value("/api/inventory/owners/" + missing));
    }

    @Test
    void maintainsAllDirectRelationshipsAndRejectsDuplicateAndSelfEdges() throws Exception {
        String suffix = UUID.randomUUID().toString();
        BusinessService service = inventory.createBusinessService("Map " + suffix, BusinessCriticality.HIGH);
        SoftwareComponent component = inventory.createSoftwareComponent("npm", "app-" + suffix, "1");
        SoftwareComponent dependency = inventory.createSoftwareComponent("npm", "lib-" + suffix, "1");
        ThirdPartyVendor vendor = inventory.createThirdPartyVendor("Cloud " + suffix);
        Owner owner = inventory.createOwner("Team " + suffix);

        String serviceComponent = "/api/inventory/services/" + service.getId() + "/components/" + component.getId();
        String serviceVendor = "/api/inventory/services/" + service.getId() + "/vendors/" + vendor.getId();
        String serviceOwner = "/api/inventory/services/" + service.getId() + "/owners/" + owner.getId();
        String dependencyPath = "/api/inventory/components/" + component.getId()
                + "/dependencies/" + dependency.getId();

        for (String path : new String[]{serviceComponent, serviceVendor, serviceOwner, dependencyPath}) {
            mvc.perform(post(path).with(role("ANALYST")))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", path));
        }

        mvc.perform(get("/api/inventory/services/{id}/components", service.getId()).with(role("SERVICE_OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(1)));
        mvc.perform(get("/api/inventory/services/{id}/vendors", service.getId()).with(role("SERVICE_OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(1)));
        mvc.perform(get("/api/inventory/services/{id}/owners", service.getId()).with(role("SERVICE_OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(1)));
        mvc.perform(get("/api/inventory/components/{id}/dependencies", component.getId()).with(role("SERVICE_OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(1)));

        mvc.perform(post(serviceComponent).with(role("ANALYST")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
        mvc.perform(post("/api/inventory/components/{componentId}/dependencies/{dependencyId}",
                        component.getId(), component.getId())
                        .with(role("ANALYST")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        for (String path : new String[]{serviceComponent, serviceVendor, serviceOwner, dependencyPath}) {
            mvc.perform(delete(path).with(role("COMPLIANCE_ADMIN"))).andExpect(status().isNoContent());
        }
        mvc.perform(delete(serviceComponent).with(role("ANALYST")))
                .andExpect(status().isNotFound());
    }

    @Test
    void filtersListsByNameCriticalityEcosystemAndDateAndExposesEcosystems() throws Exception {
        // A collision-proof token so `search=` isolates exactly this test's rows in the shared DB.
        String tag = "flt" + UUID.randomUUID().toString().replace("-", "");
        String ecosystem = "eco" + UUID.randomUUID().toString().replace("-", "");

        create("/api/inventory/services", "{\"name\":\"" + tag + " one\",\"criticality\":\"CRITICAL\"}");
        create("/api/inventory/services", "{\"name\":\"" + tag + " two\",\"criticality\":\"LOW\"}");

        // Name search isolates our two rows regardless of what other suites seeded.
        mvc.perform(get("/api/inventory/services?search=" + tag + "&size=50").with(role("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        // Criticality narrows to one.
        mvc.perform(get("/api/inventory/services?search=" + tag + "&criticality=CRITICAL&size=50")
                        .with(role("ANALYST")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].criticality").value("CRITICAL"));
        // Rows are created "now"; a far-past upper bound excludes them, a far-past lower bound keeps them.
        mvc.perform(get("/api/inventory/services?search=" + tag + "&to=2000-01-01T00:00:00Z&size=50")
                        .with(role("ANALYST")))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/inventory/services?search=" + tag + "&from=2000-01-01T00:00:00Z&size=50")
                        .with(role("ANALYST")))
                .andExpect(jsonPath("$.totalElements").value(2));

        create("/api/inventory/components",
                "{\"ecosystem\":\"" + ecosystem + "\",\"name\":\"" + tag + "\",\"version\":\"1.0\"}");
        create("/api/inventory/components",
                "{\"ecosystem\":\"npm\",\"name\":\"" + tag + "\",\"version\":\"2.0\"}");

        mvc.perform(get("/api/inventory/components?search=" + tag + "&size=50").with(role("ANALYST")))
                .andExpect(jsonPath("$.totalElements").value(2));
        // Ecosystem is an exact (case-insensitive) match; our unique ecosystem selects one component.
        mvc.perform(get("/api/inventory/components?ecosystem=" + ecosystem + "&size=50").with(role("ANALYST")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].ecosystem").value(ecosystem));
        // Search matches the version too.
        mvc.perform(get("/api/inventory/components?search=2.0&ecosystem=npm&size=50").with(role("ANALYST")))
                .andExpect(jsonPath("$.content[*].name", hasItem(tag)));
        // The ecosystems endpoint lists our distinct ecosystem for the filter select.
        mvc.perform(get("/api/inventory/components/ecosystems").with(role("SERVICE_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItem(ecosystem)));
    }

    @Test
    void componentSearchCanExcludeComponentsAlreadyLinkedToAService() throws Exception {
        String tag = "lnk" + UUID.randomUUID().toString().replace("-", "");
        BusinessService service = inventory.createBusinessService("Svc " + tag, BusinessCriticality.LOW);
        UUID linkedId = create("/api/inventory/components",
                "{\"ecosystem\":\"npm\",\"name\":\"" + tag + "-linked\",\"version\":\"1.0\"}");
        create("/api/inventory/components",
                "{\"ecosystem\":\"npm\",\"name\":\"" + tag + "-free\",\"version\":\"1.0\"}");

        mvc.perform(post("/api/inventory/services/{sid}/components/{cid}", service.getId(), linkedId)
                        .with(role("ANALYST")))
                .andExpect(status().isCreated());

        // Both match the tag with no exclusion…
        mvc.perform(get("/api/inventory/components?search=" + tag + "&size=50").with(role("ANALYST")))
                .andExpect(jsonPath("$.totalElements").value(2));
        // …but the picker excludes the one already linked to this service.
        mvc.perform(get("/api/inventory/components?search=" + tag + "&excludeLinkedToService=" + service.getId()
                        + "&size=50").with(role("ANALYST")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value(tag + "-free"));
    }

    private UUID create(String path, String json) throws Exception {
        MvcResult result = mvc.perform(post(path)
                        .with(role("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith(path + "/")))
                .andReturn();
        Matcher matcher = ID_PATTERN.matcher(result.getResponse().getContentAsString());
        if (!matcher.find()) {
            throw new AssertionError("Created response did not contain an id");
        }
        return UUID.fromString(matcher.group(1));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor role(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
