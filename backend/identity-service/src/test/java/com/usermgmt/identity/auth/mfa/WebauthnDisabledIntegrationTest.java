package com.usermgmt.identity.auth.mfa;

import com.usermgmt.identity.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With WebAuthn disabled for the deployment, passkey enrollment is refused. */
@SpringBootTest(properties = {
        "identity.mfa.email-otp.enabled=false",
        "identity.mfa.webauthn.enabled=false",
        "identity.rate-limit.enabled=false"
})
class WebauthnDisabledIntegrationTest extends AbstractIntegrationTest {

    @Test
    void begin_is_rejected_when_webauthn_is_disabled() throws Exception {
        registerUser("wa-off@example.com", "password123", "Off");
        String token = login("wa-off@example.com", "password123");
        mvc.perform(post("/auth/mfa/webauthn/register/begin").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }
}
