package com.usermgmt.identity.auth.mfa.totp;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** RFC 4226/6238 conformance and round-trips for the hand-rolled TOTP. */
class TotpServiceTest {

    private final TotpService totp = new TotpService(30, 6, 1, "Identity");

    // The reference shared secret from RFC 4226 Appendix D / RFC 6238.
    private final String secret =
            TotpService.base32Encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void matches_rfc_reference_codes() {
        assertThat(totp.generateCode(secret, 0)).isEqualTo("755224");   // counter 0
        assertThat(totp.generateCode(secret, 30)).isEqualTo("287082");  // counter 1
        assertThat(totp.generateCode(secret, 60)).isEqualTo("359152");  // counter 2
    }

    @Test
    void base32_round_trips() {
        byte[] original = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertThat(TotpService.base32Decode(TotpService.base32Encode(original))).isEqualTo(original);
    }

    @Test
    void verify_accepts_a_current_code_and_rejects_a_wrong_one() {
        String freshSecret = totp.newSecret();
        String current = totp.generateCode(freshSecret, Instant.now().getEpochSecond());

        assertThat(totp.verify(freshSecret, current)).isTrue();
        assertThat(totp.verify(freshSecret, "000000".equals(current) ? "111111" : "000000")).isFalse();
    }
}
