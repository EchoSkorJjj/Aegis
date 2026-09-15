package com.usermgmt.identity.org;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.usermgmt.identity.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR 0001: registration creates an <em>org-less</em> identity; organizations are created separately
 * via {@code POST /orgs}, which makes the creator that org's {@code ORG_ADMIN}. (Signup no longer
 * takes an organization name — that is the "create or join an organization" step after login.)
 */
class OrgSignupIntegrationTest extends AbstractIntegrationTest {

    @Test
    void registrationCreatesAnOrgLessIdentity() throws Exception {
        String email = "solo-" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", email, "password", "password123", "displayName", "Solo")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles", contains("USER")));

        String token = login(email, "password123");
        // No memberships yet — this is the "create or join an organization" state.
        mvc.perform(get("/orgs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void creatingAnOrganizationMakesTheCallerItsOwner() throws Exception {
        String email = "founder-" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", email, "password", "password123", "displayName", "Founder")))
                .andExpect(status().isCreated());
        String token = login(email, "password123");

        String created = mvc.perform(post("/orgs").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("name", "Acme Bank")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Acme Bank"))
                .andExpect(jsonPath("$.roles", hasItem("ORG_ADMIN")))
                .andReturn().getResponse().getContentAsString();
        String orgId = JsonPath.read(created, "$.id");

        mvc.perform(get("/orgs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(orgId));
    }
}
