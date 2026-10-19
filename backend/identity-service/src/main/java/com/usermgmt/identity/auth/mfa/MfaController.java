package com.usermgmt.identity.auth.mfa;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import com.usermgmt.identity.auth.mfa.MfaDtos.MfaStatusResponse;
import com.usermgmt.identity.auth.mfa.MfaDtos.RecoveryCodesResponse;
import com.usermgmt.identity.auth.mfa.MfaDtos.RegenerateRecoveryCodesRequest;
import com.usermgmt.identity.auth.mfa.MfaDtos.TotpConfirmRequest;
import com.usermgmt.identity.auth.mfa.MfaDtos.TotpDisableRequest;
import com.usermgmt.identity.auth.mfa.MfaDtos.TotpInitResponse;
import com.usermgmt.identity.auth.mfa.totp.MfaEnrollmentService;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.PasskeySummary;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.RegistrationFinishRequest;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.RegistrationFinishResponse;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.RegistrationOptionsResponse;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnEnrollmentService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated self-service MFA enrollment — the authenticator (TOTP) and passkey (WebAuthn) factors. */
@RestController
@RequestMapping("/auth/mfa")
@SecurityRequirement(name = "bearer-jwt")
public class MfaController {

    private final MfaEnrollmentService enrollment;
    private final WebauthnEnrollmentService webauthn;

    public MfaController(MfaEnrollmentService enrollment, WebauthnEnrollmentService webauthn) {
        this.enrollment = enrollment;
        this.webauthn = webauthn;
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

    /** Start passkey registration: returns the WebAuthn creation options for the browser. */
    @PostMapping("/webauthn/register/begin")
    public RegistrationOptionsResponse beginWebauthnRegistration(@AuthenticationPrincipal Jwt jwt) {
        return webauthn.beginRegistration(userId(jwt));
    }

    /** Finish passkey registration: verifies the attestation and stores the credential. */
    @PostMapping("/webauthn/register/finish")
    public RegistrationFinishResponse finishWebauthnRegistration(
            @Valid @RequestBody RegistrationFinishRequest req, @AuthenticationPrincipal Jwt jwt) {
        return webauthn.finishRegistration(userId(jwt), req);
    }

    /** List the caller's registered passkeys (for the settings page). */
    @GetMapping("/webauthn/credentials")
    public List<PasskeySummary> listPasskeys(@AuthenticationPrincipal Jwt jwt) {
        return webauthn.listPasskeys(userId(jwt));
    }

    /** Remove one of the caller's passkeys. */
    @DeleteMapping("/webauthn/credentials/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePasskey(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        webauthn.deletePasskey(userId(jwt), id);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
