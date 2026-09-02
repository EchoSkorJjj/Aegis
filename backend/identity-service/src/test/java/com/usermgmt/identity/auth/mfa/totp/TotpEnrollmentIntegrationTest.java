package com.usermgmt.identity.auth.mfa.totp;

import com.jayway.jsonpath.JsonPath;
import com.usermgmt.identity.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import java.time.Instant;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Authenticator (TOTP) enrollment: init → confirm with a real code → status → disable. */
@SpringBootTest(properties = {
        "identity.mfa.email-otp.enabled=false",
        "identity.mfa.totp.enabled=true",
        "identity.rate-limit.enabled=false"
})
class TotpEnrollmentIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TotpService totp;

    @Test
    void user_enrolls_confirms_and_disables_the_authenticator() throws Exception {
        registerUser("totp@example.com", "password123", "Totp User");
        String token = login("totp@example.com", "password123"); // no factor enrolled yet → tokens directly

        // Start enrollment: secret + otpauth URI for the QR code.
        String initResponse = mvc.perform(post("/auth/mfa/totp/init")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otpauthUri").value(startsWith("otpauth://totp/")))
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String secret = JsonPath.read(initResponse, "$.secret");

        // A wrong code is rejected and leaves it disabled.
        mvc.perform(post("/auth/mfa/totp/confirm").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("code", "000000")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/auth/mfa/status").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.totpEnabled").value(false));

        // The correct current code enables it and returns a batch of recovery codes.
        mvc.perform(post("/auth/mfa/totp/confirm").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("code", totp.generateCode(secret, Instant.now().getEpochSecond()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10));
        mvc.perform(get("/auth/mfa/status").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.totpEnabled").value(true))
                .andExpect(jsonPath("$.recoveryCodesRemaining").value(10));

        // Disable, re-verifying a current code.
        mvc.perform(post("/auth/mfa/totp/disable").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("code", totp.generateCode(secret, Instant.now().getEpochSecond()))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/auth/mfa/status").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.totpEnabled").value(false));
    }
}
