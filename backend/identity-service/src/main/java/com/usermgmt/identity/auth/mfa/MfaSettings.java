package com.usermgmt.identity.auth.mfa;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Which MFA methods this deployment allows. Each is an independent on/off flag
 * ({@code identity.mfa.<method>.enabled}) — flip one env var to enable or disable a factor.
 * If no method is enabled, login is single-factor (password only).
 */
@Component
public class MfaSettings {

    private final boolean emailOtpEnabled;
    private final boolean totpEnabled;
    private final boolean webauthnEnabled;

    public MfaSettings(
            @Value("${identity.mfa.email-otp.enabled:true}") boolean emailOtpEnabled,
            @Value("${identity.mfa.totp.enabled:false}") boolean totpEnabled,
            @Value("${identity.mfa.webauthn.enabled:false}") boolean webauthnEnabled) {
        this.emailOtpEnabled = emailOtpEnabled;
        this.totpEnabled = totpEnabled;
        this.webauthnEnabled = webauthnEnabled;
    }

    public boolean isEnabled(MfaMethod method) {
        return switch (method) {
            case EMAIL_OTP -> emailOtpEnabled;
            case TOTP -> totpEnabled;
            case WEBAUTHN -> webauthnEnabled;
        };
    }

    public boolean isEmailOtpEnabled() {
        return emailOtpEnabled;
    }

    public boolean isTotpEnabled() {
        return totpEnabled;
    }

    public boolean isWebauthnEnabled() {
        return webauthnEnabled;
    }

    /** The methods this deployment allows, in preference order. */
    public List<MfaMethod> enabledMethods() {
        List<MfaMethod> methods = new ArrayList<>();
        for (MfaMethod method : MfaMethod.values()) {
            if (isEnabled(method)) {
                methods.add(method);
            }
        }
        return methods;
    }
}
