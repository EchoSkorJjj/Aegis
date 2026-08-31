package com.usermgmt.identity.auth;

import com.usermgmt.identity.audit.AuditEventType;
import com.usermgmt.identity.audit.AuditService;
import com.usermgmt.identity.common.ApiExceptions;
import com.usermgmt.identity.common.Tokens;
import com.usermgmt.identity.user.Role;
import com.usermgmt.identity.user.User;
import com.usermgmt.identity.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Email-verification and password-reset flows using single-use, hashed, time-limited tokens. */
@Service
public class AccountService {

    private final UserRepository users;
    private final AuthTokenRepository tokens;
    private final RefreshTokenRepository refreshTokens;
    private final EmailSender emailSender;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final Duration verificationTtl;
    private final Duration resetTtl;
    private final Duration inviteTtl;

    public AccountService(UserRepository users,
                          AuthTokenRepository tokens,
                          RefreshTokenRepository refreshTokens,
                          EmailSender emailSender,
                          PasswordEncoder passwordEncoder,
                          AuditService audit,
                          @Value("${identity.tokens.email-verification-hours:24}") long verificationHours,
                          @Value("${identity.tokens.password-reset-minutes:30}") long resetMinutes,
                          @Value("${identity.tokens.invite-hours:72}") long inviteHours) {
        this.users = users;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.emailSender = emailSender;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.verificationTtl = Duration.ofHours(verificationHours);
        this.resetTtl = Duration.ofMinutes(resetMinutes);
        this.inviteTtl = Duration.ofHours(inviteHours);
    }

    @Transactional
    public void sendEmailVerification(User user) {
        String raw = issueToken(user.getId(), TokenType.EMAIL_VERIFICATION, verificationTtl);
        emailSender.sendEmailVerification(user.getEmail(), raw);
        audit.event(AuditEventType.EMAIL_VERIFICATION_SENT).actor(user).log();
    }

    /** Re-sends verification; no-op (still a success response) if unknown or already verified. */
    @Transactional
    public void resendVerification(String email) {
        users.findByEmail(email.trim().toLowerCase())
                .filter(user -> !user.isEmailVerified())
                .ifPresent(this::sendEmailVerification);
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        AuthToken token = consume(rawToken, TokenType.EMAIL_VERIFICATION);
        users.findById(token.getUserId()).ifPresent(user -> {
            user.setEmailVerified(true);
            audit.event(AuditEventType.EMAIL_VERIFIED).actor(user).log();
        });
    }

    /** No-op if the email is unknown — avoids user enumeration (caller always gets the same result). */
    @Transactional
    public void requestPasswordReset(String email) {
        users.findByEmail(email.trim().toLowerCase()).ifPresent(user -> {
            String raw = issueToken(user.getId(), TokenType.PASSWORD_RESET, resetTtl);
            emailSender.sendPasswordReset(user.getEmail(), raw);
            audit.event(AuditEventType.PASSWORD_RESET_REQUESTED).actor(user).log();
        });
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        AuthToken token = consume(rawToken, TokenType.PASSWORD_RESET);
        User user = users.findById(token.getUserId())
                .orElseThrow(() -> new ApiExceptions.BadRequest("Invalid or expired token"));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        refreshTokens.revokeAllForUser(user.getId()); // existing sessions die on password change
        audit.event(AuditEventType.PASSWORD_RESET_COMPLETED).actor(user).log();
    }

    /**
     * Admin-provisioned account: creates the user with an unusable random password and emails a
     * single-use link to set their own. The real password is chosen via {@link #acceptInvite}.
     */
    @Transactional
    public User inviteUser(String email, String displayName) {
        String normalized = email.trim().toLowerCase();
        if (users.existsByEmail(normalized)) {
            throw new ApiExceptions.Conflict("Email already registered");
        }
        // A random hash no one holds — the account cannot be signed into until the invite is accepted.
        String unusablePassword = passwordEncoder.encode(Tokens.opaqueToken());
        User user = new User(UUID.randomUUID(), normalized, unusablePassword, displayName);
        user.getRoles().add(Role.USER);
        User saved = users.saveAndFlush(user); // flush so the invite-token FK sees the new row
        String raw = issueToken(saved.getId(), TokenType.INVITE, inviteTtl);
        emailSender.sendInvite(saved.getEmail(), raw);
        audit.event(AuditEventType.USER_INVITED).target(saved).detail("invite sent").log();
        return saved;
    }

    /** Completes an invite: sets the first password and verifies the email (the link proves ownership). */
    @Transactional
    public void acceptInvite(String rawToken, String newPassword) {
        AuthToken token = consume(rawToken, TokenType.INVITE);
        User user = users.findById(token.getUserId())
                .orElseThrow(() -> new ApiExceptions.BadRequest("Invalid or expired token"));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setEmailVerified(true);
        audit.event(AuditEventType.EMAIL_VERIFIED).actor(user).detail("via invite").log();
    }

    private String issueToken(UUID userId, TokenType type, Duration ttl) {
        tokens.invalidatePrevious(userId, type);
        String raw = Tokens.opaqueToken();
        tokens.save(new AuthToken(UUID.randomUUID(), userId, Tokens.sha256Hex(raw), type,
                Instant.now().plus(ttl)));
        return raw;
    }

    private AuthToken consume(String rawToken, TokenType type) {
        AuthToken token = tokens.findByTokenHash(Tokens.sha256Hex(rawToken))
                .orElseThrow(() -> new ApiExceptions.BadRequest("Invalid or expired token"));
        if (token.isUsed() || token.getType() != type || token.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiExceptions.BadRequest("Invalid or expired token");
        }
        token.setUsed(true);
        return token;
    }
}
