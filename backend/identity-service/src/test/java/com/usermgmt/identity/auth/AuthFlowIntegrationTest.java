package com.usermgmt.identity.auth;

import com.jayway.jsonpath.JsonPath;
import com.usermgmt.identity.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowIntegrationTest extends AbstractIntegrationTest {

    @Test
    void register_login_me_refresh_endToEnd() throws Exception {
        String email = "alice@example.com";

        // register -> 201, role USER
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", email, "password", "password123", "displayName", "Alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.roles", contains("USER")));

        // duplicate email -> 409
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", email, "password", "password123")))
                .andExpect(status().isConflict());

        // password too short -> 400
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", "bad@example.com", "password", "short")))
                .andExpect(status().isBadRequest());

        // login -> 200 + tokens
        String loginResponse = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", email, "password", "password123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String accessToken = JsonPath.read(loginResponse, "$.accessToken");
        String refreshToken = JsonPath.read(loginResponse, "$.refreshToken");

        // /users/me with token -> 200, correct identity
        mvc.perform(get("/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.roles", contains("USER")));

        // /users/me without token -> 401
        mvc.perform(get("/users/me")).andExpect(status().isUnauthorized());

        // a USER hitting the admin listing -> 403
        mvc.perform(get("/users").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());

        // refresh -> 200, new tokens
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("refreshToken", refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        // reusing the same refresh token -> 401 (single-use rotation)
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("refreshToken", refreshToken)))
                .andExpect(status().isUnauthorized());
    }
}
