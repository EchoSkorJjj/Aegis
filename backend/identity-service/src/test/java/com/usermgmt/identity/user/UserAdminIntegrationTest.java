package com.usermgmt.identity.user;

import java.util.Set;
import java.util.UUID;

import com.usermgmt.identity.org.Organization;
import com.usermgmt.identity.org.OrganizationMember;
import com.usermgmt.identity.org.OrganizationMemberRepository;
import com.usermgmt.identity.org.OrganizationRepository;
import com.usermgmt.identity.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserAdminIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    OrganizationRepository organizations;

    @Autowired
    OrganizationMemberRepository members;

    @Test
    void admin_can_manage_users() throws Exception {
        // Seed an admin directly in the fixture (hermetic — not via the prod bootstrap path).
        User admin = new User(UUID.randomUUID(), "root@example.com",
                passwordEncoder.encode("password123"), "Root");
        admin.getRoles().add(Role.USER);
        admin.getRoles().add(Role.PLATFORM_ADMIN);
        users.save(admin);

        // A normal user to be managed.
        registerUser("carol@example.com", "password123", "Carol");
        UUID carolId = users.findByEmail("carol@example.com").orElseThrow().getId();

        String adminToken = login("root@example.com", "password123");

        // Admin can list users.
        mvc.perform(get("/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // A platform admin can promote Carol (here, to PLATFORM_ADMIN).
        mvc.perform(put("/users/{id}/roles", carolId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"USER\",\"PLATFORM_ADMIN\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("USER", "PLATFORM_ADMIN")));

        // Admin can delete Carol.
        mvc.perform(delete("/users/{id}", carolId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void admin_can_search_users_by_text() throws Exception {
        User admin = new User(UUID.randomUUID(), "search-admin@example.com",
                passwordEncoder.encode("password123"), "Search Admin");
        admin.getRoles().add(Role.USER);
        admin.getRoles().add(Role.PLATFORM_ADMIN);
        users.save(admin);

        // Unique tokens so the counts are immune to users seeded by other test classes
        // (the Testcontainers database is shared across the suite).
        registerUser("filter-aaa@nonce.example", "password123", "Filter Aaa");
        registerUser("filter-bbb@nonce.example", "password123", "Filter Bbb");

        String adminToken = login("search-admin@example.com", "password123");

        // A term unique to one user narrows to just that user (matches email or display name).
        mvc.perform(get("/users").param("search", "filter-aaa")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].email").value("filter-aaa@nonce.example"));

        // A term the two share matches both.
        mvc.perform(get("/users").param("search", "nonce.example")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));
    }

    @Test
    void an_org_less_user_cannot_list_users() throws Exception {
        // A user who belongs to no organization has no directory to read (no org context -> 403).
        registerUser("dave@example.com", "password123", "Dave");
        String userToken = login("dave@example.com", "password123");

        mvc.perform(get("/users").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void a_member_can_read_the_directory_but_not_mutate() throws Exception {
        // ADR 0001: a domain-role member sees their org's members (read-only) but cannot invite/manage.
        Organization org = organizations.save(
                new Organization(UUID.randomUUID(), "Read Co " + UUID.randomUUID()));
        // NB: not "@nonce.example" — the shared test DB means that substring would inflate the
        // admin_can_search_users_by_text counts.
        User member = new User(UUID.randomUUID(), "reader-" + UUID.randomUUID() + "@example.com",
                passwordEncoder.encode("password123"), "Reader");
        member.getRoles().add(Role.USER);
        users.save(member);
        OrganizationMember membership =
                new OrganizationMember(UUID.randomUUID(), org.getId(), member.getId());
        membership.setRoles(Set.of(Role.SERVICE_OWNER));
        members.save(membership);

        String token = login(member.getEmail(), "password123");

        // Reads the org directory…
        mvc.perform(get("/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        // …but cannot invite / mutate.
        mvc.perform(post("/users").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", "nope-" + UUID.randomUUID() + "@example.com",
                                "displayName", "Nope")))
                .andExpect(status().isForbidden());
    }
}
