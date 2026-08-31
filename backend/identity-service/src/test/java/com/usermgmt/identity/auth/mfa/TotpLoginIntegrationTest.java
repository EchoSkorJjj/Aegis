package com.usermgmt.identity.auth.mfa;

import com.jayway.jsonpath.JsonPath;
import com.usermgmt.identity.support.AbstractIntegrationTest;
import com.usermgmt.identity.support.CapturingEmailSender;
import com.usermgmt.identity.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login with an enrolled authenticator, and switching to the email fallback via the chooser. */
@SpringBootTest(properties = {
        "identity.mfa.email-otp.enabled=true",
        "identity.mfa.totp.enabled=true",
        "identity.rate-limit.enabled=false"
})
class TotpLoginIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TotpService totp;
    @Autowired
    UserTotpRepository totps;
    @Autowired
    SecretCipher cipher;
    @Autowired
    UserRepository users;
    @Autowired
    CapturingEmailSender email;
    @Autowired
    RecoveryCodeService recoveryCodes;

    /** Seeds a confirmed authenticator directly and returns its shared secret. */
    private String enrollTotp(String userEmail) {
        UUID userId = users.findByEmail(userEmail).orElseThrow().getId();
        String secret = totp.newSecret();
        UserTotp record = new UserTotp(userId, cipher.encrypt(secret));
        record.setEnabled(true);
        totps.save(record);
        return secret;
    }

    @Test
    void enrolled_user_logs_in_with_the_authenticator() throws Exception {
        String userEmail = "totp-login@example.com";
        registerUser(userEmail, "password123", "Totp Login");
        String secret = enrollTotp(userEmail);

        // Login offers both methods and arms the stronger (TOTP) by default — no email sent.
        String response = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", userEmail, "password", "password123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("TOTP"))
                .andExpect(jsonPath("$.methods", containsInAnyOrder("EMAIL_OTP", "TOTP")))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String challengeId = JsonPath.read(response, "$.challengeId");

        // Wrong authenticator code -> 401; correct code -> tokens.
        mvc.perform(post("/auth/login/verify-otp").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("challengeId", challengeId, "code", "000000")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/login/verify-otp").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("challengeId", challengeId,
                                "code", totp.generateCode(secret, Instant.now().getEpochSecond()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void user_can_switch_to_the_email_fallback() throws Exception {
        String userEmail = "totp-fallback@example.com";
        registerUser(userEmail, "password123", "Totp Fallback");
        enrollTotp(userEmail);

        String response = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", userEmail, "password", "password123")))
                .andReturn().getResponse().getContentAsString();
        String challengeId = JsonPath.read(response, "$.challengeId");

        // Switch to email OTP: the code is emailed and the armed method flips.
        mvc.perform(post("/auth/login/select-method").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("challengeId", challengeId, "method", "EMAIL_OTP")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("EMAIL_OTP"));

        String code = email.loginOtpFor(userEmail);
        mvc.perform(post("/auth/login/verify-otp").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("challengeId", challengeId, "code", code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void a_recovery_code_signs_in_and_is_single_use() throws Exception {
        String userEmail = "totp-recovery@example.com";
        registerUser(userEmail, "password123", "Totp Recovery");
        UUID userId = users.findByEmail(userEmail).orElseThrow().getId();
        enrollTotp(userEmail);
        List<String> codes = recoveryCodes.regenerate(userId);
        String recoveryCode = codes.get(0);

        // A recovery code satisfies the (TOTP-armed) challenge.
        String first = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", userEmail, "password", "password123")))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/auth/login/verify-otp").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("challengeId", JsonPath.read(first, "$.challengeId"),
                                "code", recoveryCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        // The same code cannot be reused.
        String second = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("email", userEmail, "password", "password123")))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/auth/login/verify-otp").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonObject("challengeId", JsonPath.read(second, "$.challengeId"),
                                "code", recoveryCode)))
                .andExpect(status().isUnauthorized());
    }
}
