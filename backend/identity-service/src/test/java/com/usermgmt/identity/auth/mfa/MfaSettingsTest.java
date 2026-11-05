package com.usermgmt.identity.auth.mfa;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Each MFA method is its own switch, and login goes single-factor only when all are off. */
class MfaSettingsTest {

    @Test
    void eachMethodAnswersToItsOwnSwitch() {
        assertThat(new MfaSettings(true, false, false).enabledMethods()).containsExactly(MfaMethod.EMAIL_OTP);
        assertThat(new MfaSettings(false, true, false).enabledMethods()).containsExactly(MfaMethod.TOTP);
        assertThat(new MfaSettings(false, false, true).enabledMethods()).containsExactly(MfaMethod.WEBAUTHN);
    }

    @Test
    void reportsEachMethodConsistently() {
        MfaSettings settings = new MfaSettings(true, false, true);

        assertThat(settings.isEnabled(MfaMethod.EMAIL_OTP)).isEqualTo(settings.isEmailOtpEnabled()).isTrue();
        assertThat(settings.isEnabled(MfaMethod.TOTP)).isEqualTo(settings.isTotpEnabled()).isFalse();
        assertThat(settings.isEnabled(MfaMethod.WEBAUTHN)).isEqualTo(settings.isWebauthnEnabled()).isTrue();
    }

    @Test
    void noMethodMeansNoSecondFactor() {
        assertThat(new MfaSettings(false, false, false).enabledMethods()).isEmpty();
    }
}
