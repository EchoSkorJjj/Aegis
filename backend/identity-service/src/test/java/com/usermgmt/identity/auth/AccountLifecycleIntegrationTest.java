package com.usermgmt.identity.auth;

import com.usermgmt.identity.support.AbstractIntegrationTest;
import com.usermgmt.identity.support.CapturingEmailSender;
import com.usermgmt.identity.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    UserRepository users;

    @Autowired
    CapturingEmailSender email;

    @Test
    void email_verification_flow() throws Exception {
        String addr = "verify@example.com";
        registerUser(addr, "password123", "Vee");

        // new accounts start unverified, and registration issues a verification token
        assertThat(users.findByEmail(addr).orElseThrow().isEmailVerified()).isFalse();
        String token = email.verificationTokenFor(addr);
        assertThat(token).isNotNull();

        mvc.perform(post("/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("token", token)))
                .andExpect(status().isNoContent());

        assertThat(users.findByEmail(addr).orElseThrow().isEmailVerified()).isTrue();
    }

    @Test
    void password_reset_flow() throws Exception {
        String addr = "reset@example.com";
        registerUser(addr, "password123", "Ree");

        mvc.perform(post("/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", addr)))
                .andExpect(status().isAccepted());

        String token = email.resetTokenFor(addr);
        assertThat(token).isNotNull();

        mvc.perform(post("/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("token", token, "newPassword", "brand-new-pass-1")))
                .andExpect(status().isNoContent());

        // old password no longer works; new one does
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", addr, "password", "password123")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", addr, "password", "brand-new-pass-1")))
                .andExpect(status().isOk());
    }

    @Test
    void forgot_password_for_unknown_email_still_returns_accepted() throws Exception {
        // no user enumeration: unknown addresses get the same 202
        mvc.perform(post("/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", "nobody@example.com")))
                .andExpect(status().isAccepted());
    }

    @Test
    void change_password_flow() throws Exception {
        String addr = "changepw@example.com";
        registerUser(addr, "password123", "Cee");
        String token = login(addr, "password123");

        // the wrong current password is rejected
        mvc.perform(post("/auth/change-password").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("currentPassword", "not-my-pass", "newPassword", "brand-new-pass-2")))
                .andExpect(status().isBadRequest());

        // the correct current password succeeds
        mvc.perform(post("/auth/change-password").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("currentPassword", "password123", "newPassword", "brand-new-pass-2")))
                .andExpect(status().isNoContent());

        // the old password no longer works; the new one does
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", addr, "password", "password123")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", addr, "password", "brand-new-pass-2")))
                .andExpect(status().isOk());
    }
}
