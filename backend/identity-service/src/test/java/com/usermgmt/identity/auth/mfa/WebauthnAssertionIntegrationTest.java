package com.usermgmt.identity.auth.mfa;

import com.jayway.jsonpath.JsonPath;
import com.usermgmt.identity.support.AbstractIntegrationTest;
import com.usermgmt.identity.support.WebauthnTestAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Passkey login (the WebAuthn assertion ceremony): once a credential is registered, the password
 * step returns a WEBAUTHN challenge which the same authenticator signs to obtain tokens. Driven by
 * the {@link WebauthnTestAuthenticator} software authenticator — no hardware.
 */
@SpringBootTest(properties = {
        "identity.mfa.email-otp.enabled=false",
        "identity.mfa.webauthn.enabled=true",
        "identity.mfa.webauthn.rp-id=localhost",
        "identity.mfa.webauthn.origin=http://localhost:3000",
        "identity.rate-limit.enabled=false"
})
class WebauthnAssertionIntegrationTest extends AbstractIntegrationTest {

    private final WebauthnTestAuthenticator authenticator = new WebauthnTestAuthenticator("http://localhost:3000");

    @Test
    void password_then_passkey_completes_login() throws Exception {
        String email = "wa-login@example.com";
        enrollPasskey(email);

        String challengeId = passwordLoginExpectingWebauthn(email);

        String options = mvc.perform(post("/auth/login/webauthn/options")
                        .contentType(MediaType.APPLICATION_JSON).content(jsonObject("challengeId", challengeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rpId").value("localhost"))
                .andExpect(jsonPath("$.allowCredentials.length()").value(1))
                .andReturn().getResponse().getContentAsString();

        mvc.perform(post("/auth/login/webauthn/verify").contentType(MediaType.APPLICATION_JSON)
                        .content(authenticator.assertionResponse(options, challengeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    void verify_rejects_a_bogus_assertion() throws Exception {
        String email = "wa-bad-login@example.com";
        String credentialId = enrollPasskey(email);
        String challengeId = passwordLoginExpectingWebauthn(email);

        // A real (registered) credential id, but the signed material is garbage → the assertion fails.
        String garbage = base64url("bad");
        String body = """
                {"challengeId":"%s","credential":{"id":"%s","type":"public-key",
                 "response":{"clientDataJSON":"%s","authenticatorData":"%s","signature":"%s"}}}
                """.formatted(challengeId, credentialId, garbage, garbage, garbage);
        mvc.perform(post("/auth/login/webauthn/verify").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers -------------------------------------------------------------

    /** Registers the user and enrolls one passkey on the shared authenticator; returns its credential id. */
    private String enrollPasskey(String email) throws Exception {
        registerUser(email, "password123", "WA User");
        String token = login(email, "password123"); // no factor yet → tokens directly
        String beginOptions = mvc.perform(post("/auth/mfa/webauthn/register/begin")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String finishResponse = mvc.perform(post("/auth/mfa/webauthn/register/finish")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authenticator.registrationResponse(beginOptions, "My Passkey")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(finishResponse, "$.credentialId");
    }

    /** Logs in with the password and asserts a WEBAUTHN challenge (not tokens); returns the challenge id. */
    private String passwordLoginExpectingWebauthn(String email) throws Exception {
        String response = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", email, "password", "password123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("WEBAUTHN"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.challengeId");
    }

    private static String base64url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes());
    }
}
