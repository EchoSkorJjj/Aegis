package com.usermgmt.identity.auth.mfa.webauthn;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Wire contracts for the WebAuthn ceremonies. Options mirror the browser's
 * {@code PublicKeyCredential{Creation,Request}OptionsJSON} and the finish requests mirror the
 * {@code {Registration,Authentication}ResponseJSON} the browser returns — all binary fields
 * base64url — so a client library such as {@code @simplewebauthn/browser} maps to them directly.
 */
public final class WebauthnDtos {

    private WebauthnDtos() {
    }

    /** A credential reference, shared by registration's excludeCredentials and login's allowCredentials. */
    public record CredentialDescriptor(String type, String id, List<String> transports) {
    }

    // --- Registration --------------------------------------------------------

    /** {@code /register/begin} response: the creation options the browser passes to the authenticator. */
    public record RegistrationOptionsResponse(
            RelyingParty rp,
            UserEntity user,
            String challenge,
            List<PublicKeyCredentialParameter> pubKeyCredParams,
            long timeout,
            List<CredentialDescriptor> excludeCredentials,
            AuthenticatorSelection authenticatorSelection,
            String attestation) {

        public record RelyingParty(String id, String name) {
        }

        public record UserEntity(String id, String name, String displayName) {
        }

        public record PublicKeyCredentialParameter(String type, long alg) {
        }

        public record AuthenticatorSelection(String residentKey, String userVerification) {
        }
    }

    /** {@code /register/finish} request: a user-chosen label plus the browser's attestation result. */
    public record RegistrationFinishRequest(
            @NotBlank String label,
            @NotNull @Valid Credential credential) {

        public record Credential(String id, String rawId, @NotNull @Valid AttestationResponse response, String type) {
        }

        public record AttestationResponse(
                @NotBlank String clientDataJSON,
                @NotBlank String attestationObject,
                List<String> transports) {
        }
    }

    /** {@code /register/finish} response: the stored credential, plus first-time recovery codes (if issued). */
    public record RegistrationFinishResponse(String credentialId, String label, List<String> recoveryCodes) {
    }

    /** A registered passkey as shown on the settings page; {@code id} is the handle used to delete it. */
    public record PasskeySummary(String id, String label, String createdAt, String lastUsedAt) {
    }

    // --- Assertion (login) ---------------------------------------------------

    /** {@code /login/webauthn/options} request: the pending login challenge to arm a passkey for. */
    public record AssertionOptionsRequest(@NotBlank String challengeId) {
    }

    /** {@code /login/webauthn/options} response: the request options the browser passes to the authenticator. */
    public record RequestOptionsResponse(
            String challenge,
            String rpId,
            List<CredentialDescriptor> allowCredentials,
            String userVerification,
            long timeout) {
    }

    /** {@code /login/webauthn/verify} request: the login challenge plus the browser's assertion result. */
    public record AssertionFinishRequest(
            @NotBlank String challengeId,
            @NotNull @Valid Credential credential) {

        public record Credential(String id, String rawId, @NotNull @Valid AssertionResponse response, String type) {
        }

        public record AssertionResponse(
                @NotBlank String clientDataJSON,
                @NotBlank String authenticatorData,
                @NotBlank String signature,
                String userHandle) {
        }
    }
}
