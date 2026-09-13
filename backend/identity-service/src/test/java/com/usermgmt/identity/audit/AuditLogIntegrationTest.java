package com.usermgmt.identity.audit;

import java.util.UUID;

import com.usermgmt.identity.support.AbstractIntegrationTest;
import com.usermgmt.identity.user.Role;
import com.usermgmt.identity.user.User;
import com.usermgmt.identity.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The audit trail records security events, is admin-only to read, and is append-only. */
class AuditLogIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void records_and_exposes_security_events() throws Exception {
        // An admin who can read the trail.
        User admin = new User(UUID.randomUUID(), "auditor@example.com",
                passwordEncoder.encode("password123"), "Auditor");
        admin.getRoles().add(Role.USER);
        admin.getRoles().add(Role.ADMIN);
        users.save(admin);

        // Auditable activity for a normal user: register, a failed login, then a successful one.
        registerUser("mallory@example.com", "password123", "Mallory"); // USER_REGISTERED
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", "mallory@example.com", "password", "wrong-password")))
                .andExpect(status().isUnauthorized());                 // LOGIN_FAILED
        login("mallory@example.com", "password123");                   // LOGIN_SUCCEEDED

        String adminToken = login("auditor@example.com", "password123");

        // The trail is readable and non-empty.
        mvc.perform(get("/audit").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isNotEmpty());

        // Filtering pins down the failed attempt, attributed to the address that was tried.
        mvc.perform(get("/audit")
                        .param("eventType", "LOGIN_FAILED")
                        .param("actorEmail", "mallory@example.com")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].eventType").value("LOGIN_FAILED"))
                .andExpect(jsonPath("$.content[0].outcome").value("FAILURE"))
                .andExpect(jsonPath("$.content[0].actorEmail").value("mallory@example.com"));
    }

    @Test
    void trail_is_not_readable_by_normal_users() throws Exception {
        registerUser("nate@example.com", "password123", "Nate");
        String userToken = login("nate@example.com", "password123");

        mvc.perform(get("/audit").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void rows_are_append_only_at_the_database() throws Exception {
        registerUser("olivia@example.com", "password123", "Olivia"); // guarantees at least one row

        assertThatThrownBy(() -> jdbc.update("update audit_events set detail = 'tamper'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from audit_events"))
                .isInstanceOf(DataAccessException.class);
    }
}
