package com.usermgmt.identity.user;

import com.usermgmt.identity.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserAdminIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void admin_can_manage_users() throws Exception {
        // Seed an admin directly in the fixture (hermetic — not via the prod bootstrap path).
        User admin = new User(UUID.randomUUID(), "root@example.com",
                passwordEncoder.encode("password123"), "Root");
        admin.getRoles().add(Role.USER);
        admin.getRoles().add(Role.ADMIN);
        users.save(admin);

        // A normal user to be managed.
        registerUser("carol@example.com", "password123", "Carol");
        UUID carolId = users.findByEmail("carol@example.com").orElseThrow().getId();

        String adminToken = login("root@example.com", "password123");

        // Admin can list users.
        mvc.perform(get("/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Admin can promote Carol to ADMIN.
        mvc.perform(put("/users/{id}/roles", carolId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"USER\",\"ADMIN\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("USER", "ADMIN")));

        // Admin can delete Carol.
        mvc.perform(delete("/users/{id}", carolId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void admin_can_search_users_by_text() throws Exception {
        User admin = new User(UUID.randomUUID(), "search-admin@example.com",
                passwordEncoder.encode("password123"), "Search Admin");
        admin.getRoles().add(Role.USER);
        admin.getRoles().add(Role.ADMIN);
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
    void normal_user_cannot_reach_admin_endpoints() throws Exception {
        registerUser("dave@example.com", "password123", "Dave");
        String userToken = login("dave@example.com", "password123");

        mvc.perform(get("/users").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }
}
