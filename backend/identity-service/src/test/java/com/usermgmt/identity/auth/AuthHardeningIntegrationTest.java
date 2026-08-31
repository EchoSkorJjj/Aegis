package com.usermgmt.identity.auth;

import com.jayway.jsonpath.JsonPath;
import com.usermgmt.identity.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthHardeningIntegrationTest extends AbstractIntegrationTest {

    private String loginAndGet(String email, String password, String jsonPath) throws Exception {
        String response = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", email, "password", password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, jsonPath);
    }

    @Test
    void logout_revokes_the_refresh_token() throws Exception {
        String email = "logout@example.com";
        registerUser(email, "password123", "Lo");
        String refreshToken = loginAndGet(email, "password123", "$.refreshToken");

        mvc.perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("refreshToken", refreshToken)))
                .andExpect(status().isNoContent());

        // the revoked token can no longer be exchanged
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("refreshToken", refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_all_revokes_every_session() throws Exception {
        String email = "logoutall@example.com";
        registerUser(email, "password123", "La");
        String accessToken = loginAndGet(email, "password123", "$.accessToken");
        String refreshToken = loginAndGet(email, "password123", "$.refreshToken");

        mvc.perform(post("/auth/logout-all").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("refreshToken", refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void account_locks_after_repeated_failed_logins() throws Exception {
        String email = "locky@example.com";
        registerUser(email, "password123", "Locky");

        // default threshold is 5 failed attempts
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(jsonObject("email", email, "password", "wrong-password")))
                    .andExpect(status().isUnauthorized());
        }

        // now locked: even the correct password is refused with 423 Locked
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", email, "password", "password123")))
                .andExpect(status().isLocked());
    }
}
