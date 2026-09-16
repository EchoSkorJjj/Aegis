package com.usermgmt.identity.org;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.usermgmt.identity.support.AbstractIntegrationTest;
import com.usermgmt.identity.user.User;
import com.usermgmt.identity.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 1 (ADR 0001): the access token carries the {@code org} claim for org members, and omits it
 * for the org-less platform admin.
 */
class OrgJwtClaimIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private OrganizationMemberRepository members;

    @Autowired
    private UserRepository users;

    @Test
    void accessTokenCarriesOrgClaimForMembersAndOmitsItForOrglessUsers() throws Exception {
        String memberEmail = "member-" + UUID.randomUUID() + "@example.com";
        register(memberEmail);
        Organization org = organizations.save(new Organization(UUID.randomUUID(), "Acme " + UUID.randomUUID()));
        User member = users.findByEmail(memberEmail).orElseThrow();
        member.setLastActiveOrgId(org.getId());
        users.save(member);
        // The org claim now follows an actual membership (ADR 0001), not just the last-active hint.
        members.save(new OrganizationMember(UUID.randomUUID(), org.getId(), member.getId()));

        assertThat(orgClaim(login(memberEmail))).isEqualTo(org.getId().toString());

        String orglessEmail = "ops-" + UUID.randomUUID() + "@example.com";
        register(orglessEmail);
        assertThat(orgClaim(login(orglessEmail))).isNull();
    }

    private void register(String email) throws Exception {
        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", email, "password", "password123", "displayName", "Test")))
                .andExpect(status().isCreated());
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", email, "password", "password123")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    /** Reads the {@code org} claim straight from the JWT payload — no decoder needed. */
    private static String orgClaim(String accessToken) {
        String payload = new String(
                Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
        try {
            return JsonPath.read(payload, "$.org");
        } catch (PathNotFoundException absent) {
            return null;
        }
    }
}
