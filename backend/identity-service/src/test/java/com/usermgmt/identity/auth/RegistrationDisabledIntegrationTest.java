package com.usermgmt.identity.auth;

import com.usermgmt.identity.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Invite-only mode: with registration disabled, /auth/register is forbidden and /auth/config says so. */
@SpringBootTest(properties = {
        "identity.registration.enabled=false",
        "identity.rate-limit.enabled=false"
})
class RegistrationDisabledIntegrationTest extends AbstractIntegrationTest {

    @Test
    void register_is_forbidden_and_config_reflects_it() throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", "blocked@example.com",
                                "password", "password123", "displayName", "Blocked")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/auth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationEnabled").value(false))
                .andExpect(jsonPath("$.organizationSignupEnabled").value(true));
    }
}
