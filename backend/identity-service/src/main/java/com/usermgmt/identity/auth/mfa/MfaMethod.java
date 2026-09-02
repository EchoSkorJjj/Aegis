package com.usermgmt.identity.auth.mfa;

/** The second-factor methods a login challenge can use. Each is independently enabled by config. */
public enum MfaMethod {
    /** Emailed one-time code — the baseline factor; needs no per-user enrollment. */
    EMAIL_OTP,
    /** Authenticator-app time-based code (RFC 6238) — opt-in per user. */
    TOTP,
    /** WebAuthn / passkey — opt-in per user. */
    WEBAUTHN
}
