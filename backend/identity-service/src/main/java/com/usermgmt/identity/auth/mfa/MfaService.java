package com.usermgmt.identity.auth.mfa;

import com.usermgmt.identity.audit.AuditEventType;
import com.usermgmt.identity.audit.AuditService;
import com.usermgmt.identity.auth.email.EmailSender;
import com.usermgmt.identity.auth.mfa.recovery.RecoveryCodeService;
import com.usermgmt.identity.auth.mfa.totp.SecretCipher;
import com.usermgmt.identity.auth.mfa.totp.TotpService;
import com.usermgmt.identity.auth.mfa.totp.UserTotpRepository;
import com.usermgmt.identity.auth.mfa.webauthn.WebauthnCredentialRepository;
import com.usermgmt.identity.common.ApiExceptions;
import com.usermgmt.identity.common.Tokens;
import com.usermgmt.identity.user.User;
import com.usermgmt.identity.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the second-factor step of login. {@link #availableFor} decides which factors the user may
 * use (deployment-enabled ∩ enrolled); {@link #start} arms the strongest by default; the user may
 * switch via {@link #selectMethod}; and {@link #verify} checks the answer against the armed method.
 * Every factor plugs in here without touching the login flow in {@link AuthService}.
 */
@Service
public class MfaService {

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MfaSettings settings;
    private final LoginChallengeRepository challenges;
    private final UserTotpRepository totps;
    private final WebauthnCredentialRepository webauthnCredentials;
    private final UserRepository users;
    private final TotpService totp;
    private final SecretCipher cipher;
    private final RecoveryCodeService recoveryCodes;
    private final EmailSender emailSender;
    private final AuditService audit;
    private final int codeLength;
    private final int maxAttempts;
    private final Duration ttl;

    public MfaService(MfaSettings settings,
                      LoginChallengeRepository challenges,
                      UserTotpRepository totps,
                      WebauthnCredentialRepository webauthnCredentials,
                      UserRepository users,
                      TotpService totp,
                      SecretCipher cipher,
                      RecoveryCodeService recoveryCodes,
                      EmailSender emailSender,
                      AuditService audit,
                      @Value("${identity.mfa.email-otp.length:6}") int codeLength,
                      @Value("${identity.mfa.email-otp.max-attempts:5}") int maxAttempts,
                      @Value("${identity.mfa.email-otp.ttl-minutes:10}") long ttlMinutes) {
        this.settings = settings;
        this.challenges = challenges;
        this.totps = totps;
        this.webauthnCredentials = webauthnCredentials;
        this.users = users;
        this.totp = totp;
        this.cipher = cipher;
        this.recoveryCodes = recoveryCodes;
        this.emailSender = emailSender;
        this.audit = audit;
        this.codeLength = codeLength;
        this.maxAttempts = maxAttempts;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /**
     * The factors available to this user now: deployment-enabled ∩ what the user can use. Email OTP
     * needs no enrollment; TOTP/WebAuthn require it. An empty list means single-factor login.
     */
    public List<MfaMethod> availableFor(User user) {
        List<MfaMethod> methods = new ArrayList<>();
        if (settings.isEmailOtpEnabled()) {
            methods.add(MfaMethod.EMAIL_OTP);
        }
        if (settings.isTotpEnabled() && totps.findByUserIdAndEnabledTrue(user.getId()).isPresent()) {
            methods.add(MfaMethod.TOTP);
        }
        if (settings.isWebauthnEnabled() && userHasWebauthn(user)) {
            methods.add(MfaMethod.WEBAUTHN);
        }
        return methods;
    }

    /** Begins the challenge, arming the strongest available method (the user can switch afterwards). */
    @Transactional
    public Challenge start(User user, List<MfaMethod> methods) {
        challenges.consumeActiveForUser(user.getId());
        MfaMethod armed = preferred(methods);
        LoginChallenge challenge = new LoginChallenge(
                UUID.randomUUID(), user.getId(), armed, null, Instant.now().plus(ttl));
        arm(challenge, armed, user);
        challenges.save(challenge);
        return new Challenge(challenge.getId().toString(), methods, armed);
    }

    /** Switches the pending challenge to another available method, arms it, and returns the state. */
    @Transactional
    public Challenge selectMethod(String challengeId, MfaMethod method) {
        LoginChallenge challenge = activeChallenge(challengeId)
                .orElseThrow(() -> new ApiExceptions.Unauthorized("Invalid or expired challenge"));
        User user = users.findById(challenge.getUserId())
                .orElseThrow(() -> new ApiExceptions.Unauthorized("Invalid or expired challenge"));
        List<MfaMethod> methods = availableFor(user);
        if (!methods.contains(method)) {
            throw new ApiExceptions.BadRequest("That sign-in method is not available");
        }
        challenge.setMethod(method);
        challenge.setAttempts(0);
        arm(challenge, method, user);
        return new Challenge(challenge.getId().toString(), methods, method);
    }

    /**
     * Verifies a code against the challenge's armed method. Returns the user id on success; on
     * failure it records the attempt (invalidating the challenge once attempts are exhausted).
     * Own transaction (invoked from another bean) so the attempt count survives a thrown 401.
     */
    @Transactional
    public Optional<UUID> verify(String challengeId, String code) {
        LoginChallenge challenge = activeChallenge(challengeId).orElse(null);
        if (challenge == null) {
            log.warn("MFA verify failed: no active challenge (id={}) — expired, consumed, or unknown", challengeId);
            return Optional.empty();
        }
        // Trim once: browser/mobile "one-time-code" autofill and copy-paste often add whitespace.
        String normalized = code == null ? "" : code.trim();
        boolean methodOk = switch (challenge.getMethod()) {
            case EMAIL_OTP -> challenge.getCodeHash() != null
                    && challenge.getCodeHash().equals(Tokens.sha256Hex(normalized));
            case TOTP -> verifyTotp(challenge.getUserId(), normalized);
            case WEBAUTHN -> false; // added with WebAuthn
        };
        // A single-use recovery code is a universal fallback for any MFA-enrolled account.
        boolean recoveryUsed = !methodOk && recoveryCodes.consume(challenge.getUserId(), normalized);
        if (!methodOk && !recoveryUsed) {
            challenge.setAttempts(challenge.getAttempts() + 1);
            if (challenge.getAttempts() >= maxAttempts) {
                challenge.setConsumed(true);
            }
            log.warn("MFA verify failed: method={} submittedLength={} attempt={}/{}",
                    challenge.getMethod(), normalized.length(), challenge.getAttempts(), maxAttempts);
            return Optional.empty();
        }
        challenge.setConsumed(true);
        if (recoveryUsed) {
            audit.event(AuditEventType.MFA_RECOVERY_CODE_USED).actor(challenge.getUserId()).log();
        }
        return Optional.of(challenge.getUserId());
    }

    /**
     * Arms a method on the challenge: email OTP sends a code, WebAuthn issues an assertion challenge,
     * TOTP needs nothing (the authenticator app generates the code).
     */
    private void arm(LoginChallenge challenge, MfaMethod method, User user) {
        challenge.setCodeHash(null);
        challenge.setWebauthnChallenge(null);
        switch (method) {
            case EMAIL_OTP -> {
                String code = Tokens.numericOtp(codeLength);
                challenge.setCodeHash(Tokens.sha256Hex(code));
                emailSender.sendLoginOtp(user.getEmail(), code);
            }
            case WEBAUTHN -> challenge.setWebauthnChallenge(newWebauthnChallenge());
            case TOTP -> { /* app-generated code; nothing to arm */ }
        }
    }

    /** A fresh base64url assertion challenge for a passkey login. */
    private static String newWebauthnChallenge() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean verifyTotp(UUID userId, String code) {
        return totps.findByUserIdAndEnabledTrue(userId)
                .map(record -> totp.verify(cipher.decrypt(record.getSecretEnc()), code))
                .orElse(false);
    }

    private Optional<LoginChallenge> activeChallenge(String challengeId) {
        UUID id;
        try {
            id = UUID.fromString(challengeId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        LoginChallenge challenge = challenges.findById(id).orElse(null);
        if (challenge == null || challenge.isConsumed()
                || challenge.getExpiresAt().isBefore(Instant.now())
                || challenge.getAttempts() >= maxAttempts) {
            return Optional.empty();
        }
        return Optional.of(challenge);
    }

    /** Strongest-first default: WebAuthn > TOTP > email OTP. */
    private MfaMethod preferred(List<MfaMethod> methods) {
        if (methods.contains(MfaMethod.WEBAUTHN)) {
            return MfaMethod.WEBAUTHN;
        }
        if (methods.contains(MfaMethod.TOTP)) {
            return MfaMethod.TOTP;
        }
        return MfaMethod.EMAIL_OTP;
    }

    private boolean userHasWebauthn(User user) {
        return webauthnCredentials.existsByUserId(user.getId());
    }

    /** A started challenge: its id, the methods the user may use, and the one already armed. */
    public record Challenge(String id, List<MfaMethod> methods, MfaMethod armed) {
    }
}
