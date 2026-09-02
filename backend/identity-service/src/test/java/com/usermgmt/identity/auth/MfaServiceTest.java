package com.usermgmt.identity.auth;

import com.usermgmt.identity.auth.mfa.MfaMethod;
import com.usermgmt.identity.auth.mfa.MfaService;
import com.usermgmt.identity.auth.mfa.MfaSettings;
import com.usermgmt.identity.auth.mfa.totp.UserTotp;
import com.usermgmt.identity.auth.mfa.totp.UserTotpRepository;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnCredentialRepository;
import com.usermgmt.identity.user.User;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The available-methods logic: deployment-enabled factors ∩ what the user has enrolled. */
class MfaServiceTest {

    private final UserTotpRepository totps = mock(UserTotpRepository.class);
    private final WebauthnCredentialRepository webauthnCredentials = mock(WebauthnCredentialRepository.class);

    private User user() {
        return new User(UUID.randomUUID(), "user@example.com", "hash", "User");
    }

    private MfaService mfa(boolean emailOtp, boolean totp, boolean webauthn) {
        return new MfaService(new MfaSettings(emailOtp, totp, webauthn),
                null, totps, webauthnCredentials, null, null, null, null, null, null, 6, 5, 10);
    }

    @Test
    void email_otp_is_available_when_enabled_needing_no_enrollment() {
        assertThat(mfa(true, false, false).availableFor(user())).containsExactly(MfaMethod.EMAIL_OTP);
    }

    @Test
    void no_methods_enabled_means_single_factor_login() {
        assertThat(mfa(false, false, false).availableFor(user())).isEmpty();
    }

    @Test
    void enabled_but_unenrolled_totp_is_not_offered() {
        when(totps.findByUserIdAndEnabledTrue(any())).thenReturn(Optional.empty());
        assertThat(mfa(true, true, false).availableFor(user())).containsExactly(MfaMethod.EMAIL_OTP);
    }

    @Test
    void enrolled_totp_is_offered_alongside_the_email_baseline() {
        User user = user();
        when(totps.findByUserIdAndEnabledTrue(user.getId()))
                .thenReturn(Optional.of(new UserTotp(user.getId(), "enc")));
        assertThat(mfa(true, true, false).availableFor(user))
                .containsExactlyInAnyOrder(MfaMethod.EMAIL_OTP, MfaMethod.TOTP);
    }

    @Test
    void enrolled_webauthn_is_offered_alongside_the_email_baseline() {
        User user = user();
        when(webauthnCredentials.existsByUserId(user.getId())).thenReturn(true);
        assertThat(mfa(true, false, true).availableFor(user))
                .containsExactlyInAnyOrder(MfaMethod.EMAIL_OTP, MfaMethod.WEBAUTHN);
    }
}
