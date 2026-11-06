package com.usermgmt.identity.user;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.usermgmt.identity.org.OrganizationMemberRepository;
import com.usermgmt.identity.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2b (ADR 0001): the /users admin API is tenant-scoped. An org admin invites into its own org
 * with roles, sees only its own org's users, cannot grant platform-level roles, and can neither read
 * nor delete another org's users.
 */
class OrgUserAdminIntegrationTest extends AbstractIntegrationTest {

    private record Owner(String email, String token, String orgId, String userId) {}

    @Autowired
    OrganizationMemberRepository members;

    @Autowired
    TransactionTemplate transactions;

    @Test
    void orgAdminManagesOnlyItsOwnTenant() throws Exception {
        Owner a = signUpOrg("Org A");
        Owner b = signUpOrg("Org B");

        // A invites a member into A's org with an org-level role.
        String memberEmail = "member-" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/users")
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + memberEmail
                                + "\",\"displayName\":\"Member\",\"roles\":[\"SERVICE_OWNER\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orgId").value(a.orgId()))
                .andExpect(jsonPath("$.roles", hasItems("USER", "SERVICE_OWNER")));

        // A's listing contains only A's tenant.
        mvc.perform(get("/users").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].orgId", everyItem(equalTo(a.orgId()))));

        // A cannot read B's owner (cross-org -> 404, not 403, to avoid enumeration).
        mvc.perform(get("/users/{id}", b.userId()).header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isNotFound());

        // A cannot delete B's owner either.
        mvc.perform(delete("/users/{id}", b.userId()).header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isNotFound());

        // Privilege escalation blocked: an org admin may not grant a platform-level role.
        mvc.perform(post("/users")
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x-" + UUID.randomUUID()
                                + "@example.com\",\"roles\":[\"PLATFORM_ADMIN\"]}"))
                .andExpect(status().isForbidden());

        // Domain-roles-only (ADR 0001): an org admin may not grant ORG_ADMIN either — org-admin
        // status comes only from creating an org or from a platform admin.
        mvc.perform(post("/users")
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"z-" + UUID.randomUUID()
                                + "@example.com\",\"roles\":[\"ORG_ADMIN\"]}"))
                .andExpect(status().isForbidden());

        // Inviting an existing account (even one that belongs to another org) adds it to this org as a
        // new membership (ADR 0001, Jira-style) — no longer a 409.
        mvc.perform(post("/users")
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", b.email(), "displayName", "Dup")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orgId").value(a.orgId()));
    }

    @Test
    void orgAdminCanEditItsOwnProfile() throws Exception {
        Owner a = signUpOrg("Org " + UUID.randomUUID());

        // An org admin renaming ITS OWN profile is a self-edit — always allowed, even though an org
        // admin may not modify *another* org admin. Regression: this used to 403 with "Only a
        // platform admin can modify an organization admin" because the self path went through the
        // same guard that protects other org admins.
        mvc.perform(put("/users/{id}", a.userId())
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("displayName", "Renamed Owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(a.userId()))
                .andExpect(jsonPath("$.displayName").value("Renamed Owner"))
                .andExpect(jsonPath("$.roles", hasItems("ORG_ADMIN")));
    }

    @Test
    void roleFilterListsOnlyMembersHoldingThatRoleInTheCallersOrg() throws Exception {
        Owner a = signUpOrg("Org " + UUID.randomUUID());
        Owner b = signUpOrg("Org " + UUID.randomUUID());
        String serviceOwner = "so-" + UUID.randomUUID() + "@example.com";
        String analyst = "analyst-" + UUID.randomUUID() + "@example.com";
        String ownerElsewhere = "elsewhere-" + UUID.randomUUID() + "@example.com";

        invite(a, serviceOwner, "SERVICE_OWNER");
        invite(a, analyst, "ANALYST");
        // The same person is only an ANALYST in A, but a SERVICE_OWNER in B.
        invite(a, ownerElsewhere, "ANALYST");
        invite(b, ownerElsewhere, "SERVICE_OWNER");

        mvc.perform(get("/users").param("role", "SERVICE_OWNER").param("size", "100")
                        .header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].email", hasItem(serviceOwner)))
                .andExpect(jsonPath("$.content[*].email", not(hasItem(analyst))))
                // Roles are per-membership: a service owner in B is not one in A.
                .andExpect(jsonPath("$.content[*].email", not(hasItem(ownerElsewhere))))
                .andExpect(jsonPath("$.content[*].roles", everyItem(hasItem("SERVICE_OWNER"))));

        mvc.perform(get("/users").param("role", "SERVICE_OWNER").param("size", "100")
                        .header("Authorization", "Bearer " + b.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].email", hasItem(ownerElsewhere)))
                .andExpect(jsonPath("$.content[*].email", not(hasItem(serviceOwner))));
    }

    @Test
    void orgAdminChangesAMembersRolesWithinItsOwnOrgOnly() throws Exception {
        Owner a = signUpOrg("Org " + UUID.randomUUID());
        Owner b = signUpOrg("Org " + UUID.randomUUID());
        String email = "both-" + UUID.randomUUID() + "@example.com";
        String id = inviteReturningId(a, email, "ANALYST");
        inviteReturningId(b, email, "SERVICE_OWNER");

        mvc.perform(put("/users/{id}/roles", id)
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"COMPLIANCE_ADMIN\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasItems("COMPLIANCE_ADMIN")))
                .andExpect(jsonPath("$.roles", not(hasItem("ANALYST"))));

        // The same person's membership of the other organization is untouched.
        mvc.perform(get("/users/{id}", id).header("Authorization", "Bearer " + b.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasItem("SERVICE_OWNER")))
                .andExpect(jsonPath("$.roles", not(hasItem("COMPLIANCE_ADMIN"))));
    }

    @Test
    void removingAMemberLeavesTheirAccountAndOtherOrganizations() throws Exception {
        Owner a = signUpOrg("Org " + UUID.randomUUID());
        Owner b = signUpOrg("Org " + UUID.randomUUID());
        String email = "leaver-" + UUID.randomUUID() + "@example.com";
        String id = inviteReturningId(a, email, "ANALYST");
        inviteReturningId(b, email, "ANALYST");

        mvc.perform(delete("/users/{id}", id).header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/users/{id}", id).header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/users/{id}", id).header("Authorization", "Bearer " + b.token()))
                .andExpect(status().isOk());
    }

    @Test
    void orgAdminCannotModifyAnotherOrgAdmin() throws Exception {
        Owner a = signUpOrg("Org " + UUID.randomUUID());
        String second = inviteReturningId(a, "second-admin-" + UUID.randomUUID() + "@example.com", "ANALYST");
        // Only a platform path makes a second org admin, so the test makes one directly.
        transactions.executeWithoutResult(status -> members
                .findByOrgIdAndUserId(UUID.fromString(a.orgId()), UUID.fromString(second)).orElseThrow()
                .setRoles(Set.of(Role.ORG_ADMIN)));

        mvc.perform(put("/users/{id}", second)
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("displayName", "Renamed")))
                .andExpect(status().isForbidden());
        mvc.perform(put("/users/{id}/roles", second)
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"ANALYST\"]}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/users/{id}", second).header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void orgAdminFiltersItsMembersByJoiningDate() throws Exception {
        Owner a = signUpOrg("Org " + UUID.randomUUID());
        String email = "dated-" + UUID.randomUUID() + "@example.com";
        invite(a, email, "ANALYST");
        Instant hourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant inAnHour = Instant.now().plus(1, ChronoUnit.HOURS);

        mvc.perform(get("/users").param("from", hourAgo.toString()).param("to", inAnHour.toString())
                        .param("size", "100").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].email", hasItem(email)));
        mvc.perform(get("/users").param("to", hourAgo.toString())
                        .param("size", "100").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].email", not(hasItem(email))));
        mvc.perform(get("/users").param("from", inAnHour.toString())
                        .param("size", "100").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].email", not(hasItem(email))));
    }

    private String inviteReturningId(Owner admin, String email, String role) throws Exception {
        String created = mvc.perform(post("/users")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"displayName\":\"Member\",\"roles\":[\"" + role + "\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.id");
    }

    private void invite(Owner admin, String email, String role) throws Exception {
        mvc.perform(post("/users")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"displayName\":\"Member\",\"roles\":[\"" + role + "\"]}"))
                .andExpect(status().isCreated());
    }

    private Owner signUpOrg(String orgName) throws Exception {
        String email = "owner-" + UUID.randomUUID() + "@example.com";
        String reg = mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", email, "password", "password123", "displayName", "Owner")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String login = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", email, "password", "password123")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bearer = JsonPath.read(login, "$.accessToken");
        // Create the org (becoming its ORG_ADMIN), then switch to it for a token scoped to that org.
        String created = mvc.perform(post("/orgs").header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("name", orgName)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orgId = JsonPath.read(created, "$.id");
        String switched = mvc.perform(post("/auth/switch-org").header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("orgId", orgId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Owner(email, JsonPath.read(switched, "$.accessToken"), orgId, JsonPath.read(reg, "$.id"));
    }
}
