package com.usermgmt.identity.auth;

import java.util.UUID;

import jakarta.validation.Valid;

import com.usermgmt.identity.auth.AuthDtos.AcceptInviteRequest;
import com.usermgmt.identity.auth.AuthDtos.AuthConfigResponse;
import com.usermgmt.identity.auth.AuthDtos.ChangePasswordRequest;
import com.usermgmt.identity.auth.AuthDtos.ForgotPasswordRequest;
import com.usermgmt.identity.auth.AuthDtos.LoginChallengeResponse;
import com.usermgmt.identity.auth.AuthDtos.LoginRequest;
import com.usermgmt.identity.auth.AuthDtos.RefreshRequest;
import com.usermgmt.identity.auth.AuthDtos.RegisterRequest;
import com.usermgmt.identity.auth.AuthDtos.ResendVerificationRequest;
import com.usermgmt.identity.auth.AuthDtos.ResetPasswordRequest;
import com.usermgmt.identity.auth.AuthDtos.SelectMethodRequest;
import com.usermgmt.identity.auth.AuthDtos.TokenResponse;
import com.usermgmt.identity.auth.AuthDtos.VerifyEmailRequest;
import com.usermgmt.identity.auth.AuthDtos.VerifyOtpRequest;
import com.usermgmt.identity.auth.account.AccountService;
import com.usermgmt.identity.auth.mfa.MfaSettings;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.AssertionFinishRequest;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.AssertionOptionsRequest;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.RequestOptionsResponse;
import com.usermgmt.identity.user.UserDtos.UserResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService auth;
    private final AccountService accounts;
    private final MfaSettings mfa;
    private final String authMode;
    private final boolean registrationEnabled;

    public AuthController(AuthService auth, AccountService accounts, MfaSettings mfa,
                          @Value("${identity.auth.mode:local}") String authMode,
                          @Value("${identity.registration.enabled:true}") boolean registrationEnabled) {
        this.auth = auth;
        this.accounts = accounts;
        this.mfa = mfa;
        this.authMode = authMode;
        this.registrationEnabled = registrationEnabled;
    }

    /** Public auth config for the SPA: login mode, self-registration, and enabled MFA methods. */
    @GetMapping("/config")
    public AuthConfigResponse config() {
        return new AuthConfigResponse(authMode, registrationEnabled, new AuthConfigResponse.Methods(
                mfa.isEmailOtpEnabled(), mfa.isTotpEnabled(), mfa.isWebauthnEnabled()));
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest req) {
        return UserResponse.from(auth.register(req.email(), req.password(), req.displayName()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        AuthDtos.LoginResult result = auth.login(req.email(), req.password());
        if (result.isMfaRequired()) {
            return ResponseEntity.ok(new LoginChallengeResponse(
                    result.challengeId(), result.methods(), result.armedMethod()));
        }
        return ResponseEntity.ok(result.tokens());
    }

    /** Chooser: switch the pending login challenge to another enrolled factor (and arm it). */
    @PostMapping("/login/select-method")
    public LoginChallengeResponse selectMethod(@Valid @RequestBody SelectMethodRequest req) {
        return auth.selectLoginMethod(req.challengeId(), req.method());
    }

    /** Second step of 2FA: exchange the login challenge + a code (for the armed method) for tokens. */
    @PostMapping("/login/verify-otp")
    public TokenResponse verifyOtp(@Valid @RequestBody VerifyOtpRequest req) {
        return auth.verifyLoginOtp(req.challengeId(), req.code());
    }

    /** WebAuthn login step 1: request assertion options (challenge + the user's allowed credentials). */
    @PostMapping("/login/webauthn/options")
    public RequestOptionsResponse webauthnLoginOptions(@Valid @RequestBody AssertionOptionsRequest req) {
        return auth.webauthnLoginOptions(req.challengeId());
    }

    /** WebAuthn login step 2: verify the passkey assertion for the challenge and issue tokens. */
    @PostMapping("/login/webauthn/verify")
    public TokenResponse webauthnLoginVerify(@Valid @RequestBody AssertionFinishRequest req) {
        return auth.verifyLoginWebauthn(req.challengeId(), req);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return auth.refresh(req.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest req) {
        auth.logout(req.refreshToken());
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearer-jwt")
    public void logoutAll(@AuthenticationPrincipal Jwt jwt) {
        auth.logoutAll(UUID.fromString(jwt.getSubject()));
    }

    /** Authenticated self-service password change; signs the account out of all sessions. */
    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearer-jwt")
    public void changePassword(@Valid @RequestBody ChangePasswordRequest req,
                               @AuthenticationPrincipal Jwt jwt) {
        auth.changePassword(UUID.fromString(jwt.getSubject()), req.currentPassword(), req.newPassword());
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody VerifyEmailRequest req) {
        accounts.verifyEmail(req.token());
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendVerification(@Valid @RequestBody ResendVerificationRequest req) {
        accounts.resendVerification(req.email());
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        accounts.requestPasswordReset(req.email());
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        accounts.resetPassword(req.token(), req.newPassword());
    }

    /** Completes an admin invite: sets the first password (public — carries a single-use token). */
    @PostMapping("/accept-invite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acceptInvite(@Valid @RequestBody AcceptInviteRequest req) {
        accounts.acceptInvite(req.token(), req.newPassword());
    }
}
