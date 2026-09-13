package com.usermgmt.identity.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.usermgmt.identity.audit.AuditEventType;
import com.usermgmt.identity.audit.AuditOutcome;
import com.usermgmt.identity.audit.AuditService;
import com.usermgmt.identity.auth.account.AccountService;
import com.usermgmt.identity.auth.mfa.MfaMethod;
import com.usermgmt.identity.auth.mfa.MfaService;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnAssertionService;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.AssertionFinishRequest;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnDtos.RequestOptionsResponse;
import com.usermgmt.identity.common.ApiExceptions;
import com.usermgmt.identity.user.Role;
import com.usermgmt.identity.user.User;
import com.usermgmt.identity.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final LoginAttemptService loginAttempts;
    private final AccountService accountService;
    private final MfaService mfa;
    private final WebauthnAssertionService webauthnAssertion;
    private final AuditService audit;
    private final boolean recordTokenRefresh;
    private final boolean registrationEnabled;
    private final String issuer;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users,
                       RefreshTokenRepository refreshTokens,
                       PasswordEncoder passwordEncoder,
                       JwtEncoder jwtEncoder,
                       LoginAttemptService loginAttempts,
                       AccountService accountService,
                       MfaService mfa,
                       WebauthnAssertionService webauthnAssertion,
                       AuditService audit,
                       @Value("${identity.audit.record-token-refresh:true}") boolean recordTokenRefresh,
                       @Value("${identity.registration.enabled:true}") boolean registrationEnabled,
                       @Value("${identity.auth.issuer:http://localhost:8080}") String issuer,
                       @Value("${identity.jwt.access-ttl-seconds:900}") long accessTtlSeconds,
                       @Value("${identity.jwt.refresh-ttl-seconds:604800}") long refreshTtlSeconds) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.loginAttempts = loginAttempts;
        this.accountService = accountService;
        this.mfa = mfa;
        this.webauthnAssertion = webauthnAssertion;
        this.audit = audit;
        this.recordTokenRefresh = recordTokenRefresh;
        this.registrationEnabled = registrationEnabled;
        this.issuer = issuer;
        this.accessTtl = Duration.ofSeconds(accessTtlSeconds);
        this.refreshTtl = Duration.ofSeconds(refreshTtlSeconds);
    }

    @Transactional
    public User register(String email, String rawPassword, String displayName) {
        if (!registrationEnabled) {
            throw new ApiExceptions.Forbidden("Public registration is disabled");
        }
        String normalized = email.trim().toLowerCase();
        if (users.existsByEmail(normalized)) {
            throw new ApiExceptions.Conflict("Email already registered");
        }
        User user = new User(UUID.randomUUID(), normalized, passwordEncoder.encode(rawPassword), displayName);
        user.getRoles().add(Role.USER);
        User saved = users.saveAndFlush(user); // flush so the verification-token FK sees the new user row
        accountService.sendEmailVerification(saved);
        audit.event(AuditEventType.USER_REGISTERED).actor(saved).log();
        return saved;
    }

    /**
     * Not {@code @Transactional}: a failed attempt is recorded in its own transaction (via
     * {@link LoginAttemptService}) so the count survives the exception thrown here.
     */
    public AuthDtos.LoginResult login(String email, String rawPassword) {
        String normalized = email.trim().toLowerCase();
        User user = users.findByEmail(normalized).orElse(null);
        if (user == null) {
            audit.event(AuditEventType.LOGIN_FAILED).actorEmail(normalized).detail("no such account").log();
            throw new ApiExceptions.Unauthorized("Invalid credentials");
        }
        if (user.isLocked()) {
            audit.event(AuditEventType.LOGIN_LOCKED_OUT).actor(user).log();
            throw new ApiExceptions.Locked("Account temporarily locked after repeated failed logins");
        }
        if (!user.isEnabled() || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            loginAttempts.recordFailure(user.getId());
            audit.event(AuditEventType.LOGIN_FAILED).actor(user)
                    .detail(user.isEnabled() ? "bad password" : "account disabled").log();
            throw new ApiExceptions.Unauthorized("Invalid credentials");
        }
        loginAttempts.recordSuccess(user.getId());
        List<MfaMethod> methods = mfa.availableFor(user);
        if (methods.isEmpty()) {
            audit.event(AuditEventType.LOGIN_SUCCEEDED).actor(user).log();
            return AuthDtos.LoginResult.authenticated(issueTokens(user));
        }
        MfaService.Challenge challenge = mfa.start(user, methods);
        audit.event(AuditEventType.MFA_CHALLENGE_ISSUED).actor(user).log();
        return AuthDtos.LoginResult.mfaRequired(challenge.id(), challenge.methods(), challenge.armed());
    }

    /** Second step of 2FA: exchange a login challenge id + a code (for the armed method) for tokens. */
    public AuthDtos.TokenResponse verifyLoginOtp(String challengeId, String code) {
        UUID userId = mfa.verify(challengeId, code).orElse(null);
        if (userId == null) {
            audit.event(AuditEventType.MFA_FAILED).detail("challenge=" + challengeId).log();
            throw new ApiExceptions.Unauthorized("Invalid or expired code");
        }
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiExceptions.Unauthorized("Invalid or expired code"));
        AuthDtos.TokenResponse tokens = issueTokens(user);
        audit.event(AuditEventType.MFA_SUCCEEDED).actor(user).log();
        return tokens;
    }

    /** Chooser step: re-arm the pending challenge with a different available method. */
    public AuthDtos.LoginChallengeResponse selectLoginMethod(String challengeId, MfaMethod method) {
        MfaService.Challenge challenge = mfa.selectMethod(challengeId, method);
        return new AuthDtos.LoginChallengeResponse(challenge.id(), challenge.methods(), challenge.armed());
    }

    /** WebAuthn login step: request options for the pending challenge's passkey assertion. */
    public RequestOptionsResponse webauthnLoginOptions(String challengeId) {
        return webauthnAssertion.options(challengeId);
    }

    /** WebAuthn login step: verify the passkey assertion for the pending challenge and issue tokens. */
    public AuthDtos.TokenResponse verifyLoginWebauthn(String challengeId, AssertionFinishRequest request) {
        UUID userId = webauthnAssertion.verify(challengeId, request).orElse(null);
        if (userId == null) {
            audit.event(AuditEventType.MFA_FAILED).detail("challenge=" + challengeId).log();
            throw new ApiExceptions.Unauthorized("Passkey verification failed");
        }
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiExceptions.Unauthorized("Passkey verification failed"));
        AuthDtos.TokenResponse tokens = issueTokens(user);
        audit.event(AuditEventType.MFA_SUCCEEDED).actor(user).log();
        return tokens;
    }

    /** Revokes a single refresh token (idempotent — no error if unknown or already revoked). */
    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.findByTokenHash(sha256(rawRefreshToken)).ifPresent(token -> {
            token.setRevoked(true);
            audit.event(AuditEventType.LOGOUT).actor(token.getUserId()).log();
        });
    }

    /** Revokes all of a user's active refresh tokens ("log out everywhere"). */
    @Transactional
    public void logoutAll(UUID userId) {
        refreshTokens.revokeAllForUser(userId);
        audit.event(AuditEventType.LOGOUT_ALL).actor(userId).log();
    }

    /**
     * Changes the authenticated user's own password after re-verifying the current one. Every
     * existing session is revoked, so a password change signs the account out everywhere.
     */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiExceptions.Unauthorized("Not authenticated"));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            audit.event(AuditEventType.PASSWORD_CHANGED).actor(user)
                    .outcome(AuditOutcome.FAILURE).detail("wrong current password").log();
            throw new ApiExceptions.BadRequest("Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        refreshTokens.revokeAllForUser(userId); // every existing session dies on a password change
        audit.event(AuditEventType.PASSWORD_CHANGED).actor(user).log();
    }

    /** Rotating refresh: the presented token is single-use and revoked on exchange. */
    @Transactional
    public AuthDtos.TokenResponse refresh(String rawRefreshToken) {
        RefreshToken stored = refreshTokens.findByTokenHash(sha256(rawRefreshToken))
                .orElseThrow(() -> new ApiExceptions.Unauthorized("Invalid refresh token"));
        if (stored.isRevoked() || stored.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiExceptions.Unauthorized("Refresh token expired or revoked");
        }
        stored.setRevoked(true);
        User user = users.findById(stored.getUserId())
                .orElseThrow(() -> new ApiExceptions.Unauthorized("Invalid refresh token"));
        AuthDtos.TokenResponse tokens = issueTokens(user);
        if (recordTokenRefresh) {
            audit.event(AuditEventType.TOKEN_REFRESHED).actor(user).log();
        }
        return tokens;
    }

    private AuthDtos.TokenResponse issueTokens(User user) {
        Instant now = Instant.now();
        List<String> roleNames = user.getRoles().stream().map(Enum::name).toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(accessTtl))
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("roles", roleNames)
                .build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

        String rawRefresh = newOpaqueToken();
        refreshTokens.save(new RefreshToken(
                UUID.randomUUID(), user.getId(), sha256(rawRefresh), now.plus(refreshTtl)));

        return new AuthDtos.TokenResponse(accessToken, rawRefresh, "Bearer", accessTtl.toSeconds());
    }

    private String newOpaqueToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
