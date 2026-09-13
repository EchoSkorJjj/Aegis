package com.usermgmt.identity.user;

import java.util.UUID;

import com.usermgmt.identity.support.AbstractIntegrationTest;
import com.usermgmt.identity.support.CapturingEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admin invite: create an account → the user sets their password via the emailed link → logs in. */
class AdminInviteIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    CapturingEmailSender email;

    @Test
    void admin_invites_a_user_who_sets_a_password_and_signs_in() throws Exception {
        User admin = new User(UUID.randomUUID(), "inviter@example.com",
                passwordEncoder.encode("password123"), "Inviter");
        admin.getRoles().add(Role.USER);
        admin.getRoles().add(Role.ADMIN);
        users.save(admin);
        String adminToken = login("inviter@example.com", "password123");

        // Admin provisions the account (starts unverified, no usable password).
        mvc.perform(post("/users").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", "invited@example.com", "displayName", "Invited")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("invited@example.com"))
                .andExpect(jsonPath("$.emailVerified").value(false));

        String token = email.inviteTokenFor("invited@example.com");
        assertThat(token).as("an invite link should have been emailed").isNotNull();

        // The account can't be signed into until the invite is accepted.
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", "invited@example.com", "password", "guessing1234")))
                .andExpect(status().isUnauthorized());

        // Accept the invite → set a real password.
        mvc.perform(post("/auth/accept-invite").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("token", token, "newPassword", "my-new-pass-1")))
                .andExpect(status().isNoContent());

        // Now they can sign in, and accepting verified their email.
        login("invited@example.com", "my-new-pass-1");
        assertThat(users.findByEmail("invited@example.com").orElseThrow().isEmailVerified()).isTrue();
    }

    @Test
    void a_non_admin_cannot_invite() throws Exception {
        registerUser("plain@example.com", "password123", "Plain");
        String token = login("plain@example.com", "password123");

        mvc.perform(post("/users").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", "nope@example.com", "displayName", "Nope")))
                .andExpect(status().isForbidden());
    }
}
