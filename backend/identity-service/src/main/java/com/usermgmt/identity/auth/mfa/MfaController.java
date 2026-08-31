package com.usermgmt.identity.auth.mfa;

import com.usermgmt.identity.auth.mfa.MfaDtos.MfaStatusResponse;
import com.usermgmt.identity.auth.mfa.MfaDtos.RecoveryCodesResponse;
import com.usermgmt.identity.auth.mfa.MfaDtos.RegenerateRecoveryCodesRequest;
import com.usermgmt.identity.auth.mfa.MfaDtos.TotpConfirmRequest;
import com.usermgmt.identity.auth.mfa.MfaDtos.TotpDisableRequest;
import com.usermgmt.identity.auth.mfa.MfaDtos.TotpInitResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Authenticated self-service MFA enrollment (currently the authenticator/TOTP factor). */
@RestController
@RequestMapping("/auth/mfa")
@SecurityRequirement(name = "bearer-jwt")
public class MfaController {

    private final MfaEnrollmentService enrollment;

    public MfaController(MfaEnrollmentService enrollment) {
        this.enrollment = enrollment;
    }

    /** The caller's own MFA enrollment state, for the settings page. */
    @GetMapping("/status")
    public MfaStatusResponse status(@AuthenticationPrincipal Jwt jwt) {
        return enrollment.status(userId(jwt));
    }

    /** Start authenticator enrollment: returns the secret + otpauth URI to render as a QR code. */
    @PostMapping("/totp/init")
    public TotpInitResponse initTotp(@AuthenticationPrincipal Jwt jwt) {
        return enrollment.initTotp(userId(jwt));
    }

    /** Confirm enrollment with a code from the app, enabling the factor. Returns the backup codes. */
    @PostMapping("/totp/confirm")
    public RecoveryCodesResponse confirmTotp(@Valid @RequestBody TotpConfirmRequest req,
                                             @AuthenticationPrincipal Jwt jwt) {
        return new RecoveryCodesResponse(enrollment.confirmTotp(userId(jwt), req.code()));
    }

    /** Rotate the backup (recovery) codes, re-verifying a current authenticator code. */
    @PostMapping("/recovery-codes/regenerate")
    public RecoveryCodesResponse regenerateRecoveryCodes(
            @Valid @RequestBody RegenerateRecoveryCodesRequest req, @AuthenticationPrincipal Jwt jwt) {
        return new RecoveryCodesResponse(enrollment.regenerateRecoveryCodes(userId(jwt), req.code()));
    }

    /** Turn the authenticator off (re-verifying a current code). */
    @PostMapping("/totp/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disableTotp(@Valid @RequestBody TotpDisableRequest req, @AuthenticationPrincipal Jwt jwt) {
        enrollment.disableTotp(userId(jwt), req.code());
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
