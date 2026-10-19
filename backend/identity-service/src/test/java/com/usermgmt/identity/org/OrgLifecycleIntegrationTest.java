package com.usermgmt.identity.org;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.usermgmt.identity.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 (ADR 0001): a user can create and list organizations and switch the active org. Each access
 * token is scoped to one org — its {@code org} claim plus that membership's roles — and switching to
 * an org the user does not belong to is refused.
 */
class OrgLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createsSwitchesAndScopesTokensPerOrg() throws Exception {
        // Register an org-less identity (ADR 0001 — signup no longer creates an org).
        String email = "founder-" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", email, "password", "password123", "displayName", "Founder")))
                .andExpect(status().isCreated());
        String token = login(email, "password123");
        assertThat(orgClaim(token)).as("a fresh account has no active org").isNull();

        // Create org A, then org B — the caller owns both.
        String orgA = JsonPath.read(createOrg(token, "Org A"), "$.id");
        String createB = createOrg(token, "Org B");
        String orgB = JsonPath.read(createB, "$.id");
        assertThat(orgB).isNotEqualTo(orgA);

        // The switcher lists both memberships.
        mvc.perform(get("/orgs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // Switching to A scopes the token to A.
        assertThat(orgClaim(switchOrg(token, orgA))).isEqualTo(orgA);

        // Switching to B scopes the token to B and carries ORG_ADMIN there.
        String tokenB = switchOrg(token, orgB);
        assertThat(orgClaim(tokenB)).isEqualTo(orgB);
        assertThat(rolesClaim(tokenB)).contains("ORG_ADMIN");

        // Switching to an org the caller does not belong to is refused.
        mvc.perform(post("/auth/switch-org").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("orgId", UUID.randomUUID().toString())))
                .andExpect(status().isForbidden());
    }

    /** Creates an org and returns the raw JSON response body. */
    private String createOrg(String token, String name) throws Exception {
        return mvc.perform(post("/orgs").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("name", name)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles", hasItem("ORG_ADMIN")))
                .andReturn().getResponse().getContentAsString();
    }

    /** Switches the active org and returns the new access token. */
    private String switchOrg(String token, String orgId) throws Exception {
        String body = mvc.perform(post("/auth/switch-org").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("orgId", orgId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private static String orgClaim(String accessToken) {
        try {
            return JsonPath.read(payload(accessToken), "$.org");
        } catch (PathNotFoundException absent) {
            return null;
        }
    }

    private static List<String> rolesClaim(String accessToken) {
        return JsonPath.read(payload(accessToken), "$.roles");
    }

    private static String payload(String accessToken) {
        return new String(
                Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
    }
}
