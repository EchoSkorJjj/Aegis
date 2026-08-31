package com.usermgmt.identity.auth;

import com.jayway.jsonpath.JsonPath;
import com.usermgmt.identity.support.AbstractIntegrationTest;
import com.usermgmt.identity.support.CapturingEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Two-factor login: password returns an emailed-OTP challenge, verified in a second step. */
@SpringBootTest(properties = {
        "identity.mfa.email-otp.enabled=true",
        "identity.rate-limit.enabled=false"
})
class TwoFactorLoginIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    CapturingEmailSender email;

    @Test
    void password_login_requires_emailed_otp() throws Exception {
        String userEmail = "otp-user@example.com";
        registerUser(userEmail, "password123", "Otp User");

        // Step 1: correct password returns an MFA challenge, not tokens.
        String loginResponse = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", userEmail, "password", "password123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("EMAIL_OTP"))
                .andExpect(jsonPath("$.methods[0]").value("EMAIL_OTP"))
                .andExpect(jsonPath("$.challengeId").isNotEmpty())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String challengeId = JsonPath.read(loginResponse, "$.challengeId");

        String code = email.loginOtpFor(userEmail);
        assertThat(code).as("OTP should have been emailed").isNotNull();

        // Wrong code -> 401.
        String wrongCode = "000000".equals(code) ? "111111" : "000000";
        mvc.perform(post("/auth/login/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("challengeId", challengeId, "code", wrongCode)))
                .andExpect(status().isUnauthorized());

        // Step 2: correct code -> tokens. Padded with whitespace to mirror browser/mobile
        // "one-time-code" autofill (and paste), which the backend must tolerate by trimming.
        mvc.perform(post("/auth/login/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("challengeId", challengeId, "code", "  " + code + "  ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    void mfa_config_is_exposed_publicly() throws Exception {
        mvc.perform(get("/auth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("local"))
                .andExpect(jsonPath("$.registrationEnabled").value(true))
                .andExpect(jsonPath("$.methods.emailOtp").value(true))
                .andExpect(jsonPath("$.methods.totp").value(false))
                .andExpect(jsonPath("$.methods.webauthn").value(false));
    }
}
