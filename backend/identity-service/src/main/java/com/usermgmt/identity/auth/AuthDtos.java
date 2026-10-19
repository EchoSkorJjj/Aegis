package com.usermgmt.identity.auth;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.usermgmt.identity.auth.mfa.MfaMethod;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 8, max = 100) String password,
            String displayName) {
    }

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record TokenResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresInSeconds) {
    }

    public record VerifyEmailRequest(@NotBlank String token) {
    }

    public record ResendVerificationRequest(@Email @NotBlank String email) {
    }

    public record ForgotPasswordRequest(@Email @NotBlank String email) {
    }

    public record ResetPasswordRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 8, max = 100) String newPassword) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 100) String newPassword) {
    }

    public record AcceptInviteRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 8, max = 100) String newPassword) {
    }

    public record VerifyOtpRequest(
            @NotBlank String challengeId,
            @NotBlank String code) {
    }

    /** Chooser: switch the pending login challenge to a different available second factor. */
    public record SelectMethodRequest(
            @NotBlank String challengeId,
            @NotNull MfaMethod method) {
    }

    /** Switch the caller's active organization (ADR 0001); the response is a token scoped to it. */
    public record SwitchOrgRequest(@NotNull UUID orgId) {
    }

    /**
     * Returned by /auth/login when a second factor is required: the methods the user may use and the
     * one already armed ({@code method} is null only when the user still has to choose between several).
     */
    public record LoginChallengeResponse(String challengeId, List<MfaMethod> methods, MfaMethod method) {
    }

    /** Public auth configuration the SPA reads, so one backend flag drives the whole stack. */
    public record AuthConfigResponse(
            String mode, boolean registrationEnabled, boolean organizationSignupEnabled, Methods methods) {
        public record Methods(boolean emailOtp, boolean totp, boolean webauthn) {
        }
    }

    /** Internal login outcome: either issued tokens, or a pending MFA challenge. */
    public record LoginResult(TokenResponse tokens, String challengeId,
                              List<MfaMethod> methods, MfaMethod armedMethod) {

        public static LoginResult authenticated(TokenResponse tokens) {
            return new LoginResult(tokens, null, null, null);
        }

        public static LoginResult mfaRequired(String challengeId, List<MfaMethod> methods, MfaMethod armed) {
            return new LoginResult(null, challengeId, methods, armed);
        }

        public boolean isMfaRequired() {
            return challengeId != null;
        }
    }
}
