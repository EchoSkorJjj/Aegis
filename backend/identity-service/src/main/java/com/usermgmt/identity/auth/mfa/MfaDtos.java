package com.usermgmt.identity.auth.mfa;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class MfaDtos {

    private MfaDtos() {
    }

    /** Enrollment start: the raw secret (for manual entry) and the otpauth URI (for the QR code). */
    public record TotpInitResponse(String secret, String otpauthUri) {
    }

    public record TotpConfirmRequest(@NotBlank String code) {
    }

    public record TotpDisableRequest(@NotBlank String code) {
    }

    public record RegenerateRecoveryCodesRequest(@NotBlank String code) {
    }

    /** The plaintext backup codes, returned once — on enrollment or regeneration. */
    public record RecoveryCodesResponse(List<String> recoveryCodes) {
    }

    /** What the settings page shows about the user's own MFA enrollment. */
    public record MfaStatusResponse(boolean totpEnabled, long recoveryCodesRemaining) {
    }
}
