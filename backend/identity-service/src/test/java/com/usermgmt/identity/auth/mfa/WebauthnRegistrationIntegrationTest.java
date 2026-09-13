package com.usermgmt.identity.auth.mfa;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.usermgmt.identity.support.AbstractIntegrationTest;
import com.usermgmt.identity.support.WebauthnTestAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The WebAuthn / passkey registration ceremony, driven end-to-end by a software authenticator
 * (see {@link WebauthnTestAuthenticator}) so {@code /register/finish} verifies a genuine
 * attestation — no real hardware.
 */
@SpringBootTest(properties = {
        "identity.mfa.email-otp.enabled=false",
        "identity.mfa.webauthn.enabled=true",
        "identity.mfa.webauthn.rp-id=localhost",
        "identity.mfa.webauthn.origin=http://localhost:3000",
        "identity.rate-limit.enabled=false"
})
class WebauthnRegistrationIntegrationTest extends AbstractIntegrationTest {

    private final WebauthnTestAuthenticator authenticator = new WebauthnTestAuthenticator("http://localhost:3000");

    @Test
    void begin_returns_creation_options() throws Exception {
        String token = registerAndLogin("wa-begin@example.com");
        mvc.perform(post("/auth/mfa/webauthn/register/begin").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rp.id").value("localhost"))
                .andExpect(jsonPath("$.challenge").isNotEmpty())
                .andExpect(jsonPath("$.user.name").value("wa-begin@example.com"))
                .andExpect(jsonPath("$.pubKeyCredParams.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.excludeCredentials.length()").value(0));
    }

    @Test
    void begin_requires_authentication() throws Exception {
        mvc.perform(post("/auth/mfa/webauthn/register/begin"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void full_ceremony_registers_the_passkey_and_excludes_it_next_time() throws Exception {
        String token = registerAndLogin("wa-full@example.com");
        String options = beginRegistration(token);

        String credentialId =
            JsonPath.read(completeRegistration(token, options, "My Laptop"), "$.credentialId");

        // A subsequent begin now excludes the just-registered credential — proof it persisted.
        mvc.perform(post("/auth/mfa/webauthn/register/begin").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excludeCredentials.length()").value(1))
                .andExpect(jsonPath("$.excludeCredentials[0].id").value(credentialId));
    }

    @Test
    void finish_without_begin_is_rejected() throws Exception {
        String token = registerAndLogin("wa-nobegin@example.com");
        mvc.perform(post("/auth/mfa/webauthn/register/finish").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(finishBody("id", "AA", "AA", "Nope")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void finish_with_invalid_attestation_is_rejected() throws Exception {
        String token = registerAndLogin("wa-bad@example.com");
        beginRegistration(token); // stores a real challenge...
        String garbage = base64url("not-a-real-attestation");
        mvc.perform(post("/auth/mfa/webauthn/register/finish").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(finishBody("id", garbage, garbage, "Bad")))
                .andExpect(status().isBadRequest()); // ...but the attestation doesn't verify against it
    }

    @Test
    void first_passkey_issues_recovery_codes() throws Exception {
        String token = registerAndLogin("wa-recovery@example.com");
        // Email-OTP is off here, so the passkey is the first strong factor → backup codes are issued.
        String response = completeRegistration(token, beginRegistration(token), "Key");
        List<String> codes = JsonPath.read(response, "$.recoveryCodes");
        assertThat(codes).hasSize(10);
    }

    @Test
    void passkey_is_listed_and_can_be_removed() throws Exception {
        String token = registerAndLogin("wa-manage@example.com");
        completeRegistration(token, beginRegistration(token), "My Key");

        // list → one passkey, labelled; `id` is the row handle (not the credential id) used to delete.
        String list = mvc.perform(get("/auth/mfa/webauthn/credentials")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].label").value("My Key"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(list, "$[0].id");

        mvc.perform(delete("/auth/mfa/webauthn/credentials/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/auth/mfa/webauthn/credentials").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void deleting_an_unknown_passkey_is_404() throws Exception {
        String token = registerAndLogin("wa-del404@example.com");
        mvc.perform(delete("/auth/mfa/webauthn/credentials/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // --- helpers -------------------------------------------------------------

    private String registerAndLogin(String email) throws Exception {
        registerUser(email, "password123", "WA User");
        return login(email, "password123"); // no factor enrolled yet → tokens directly
    }

    private String beginRegistration(String token) throws Exception {
        return mvc.perform(post("/auth/mfa/webauthn/register/begin").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String completeRegistration(String token, String options, String label) throws Exception {
        String finishResponse = mvc.perform(post("/auth/mfa/webauthn/register/finish")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authenticator.registrationResponse(options, label)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialId").isNotEmpty())
                .andExpect(jsonPath("$.label").value(label))
                .andReturn().getResponse().getContentAsString();
        return finishResponse;
    }

    private static String finishBody(String id, String clientDataJSON, String attestationObject, String label) {
        return """
                {"label":"%s","credential":{"id":"%s","type":"public-key",
                 "response":{"clientDataJSON":"%s","attestationObject":"%s"}}}
                """.formatted(label, id, clientDataJSON, attestationObject);
    }

    private static String base64url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes());
    }
}
