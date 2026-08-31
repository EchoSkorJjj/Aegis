package com.usermgmt.identity.auth.mfa;

import com.usermgmt.identity.audit.AuditEventType;
import com.usermgmt.identity.audit.AuditService;
import com.usermgmt.identity.auth.MfaSettings;
import com.usermgmt.identity.common.ApiExceptions;
import com.usermgmt.identity.user.User;
import com.usermgmt.identity.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Self-service enrollment for the authenticator (TOTP) factor and its backup codes. A pending
 * secret is created on {@link #initTotp} and only becomes usable once {@link #confirmTotp} proves
 * the user copied it in — which also issues the first batch of single-use recovery codes.
 */
@Service
public class MfaEnrollmentService {

    private final UserTotpRepository totps;
    private final UserRepository users;
    private final TotpService totp;
    private final SecretCipher cipher;
    private final MfaSettings settings;
    private final RecoveryCodeService recoveryCodes;
    private final AuditService audit;

    public MfaEnrollmentService(UserTotpRepository totps, UserRepository users, TotpService totp,
                                SecretCipher cipher, MfaSettings settings,
                                RecoveryCodeService recoveryCodes, AuditService audit) {
        this.totps = totps;
        this.users = users;
        this.totp = totp;
        this.cipher = cipher;
        this.settings = settings;
        this.recoveryCodes = recoveryCodes;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public MfaDtos.MfaStatusResponse status(UUID userId) {
        boolean enabled = totps.findByUserIdAndEnabledTrue(userId).isPresent();
        return new MfaDtos.MfaStatusResponse(enabled, enabled ? recoveryCodes.remaining(userId) : 0);
    }

    /** Generates a fresh (pending) secret and returns it plus the otpauth URI for the QR code. */
    @Transactional
    public MfaDtos.TotpInitResponse initTotp(UUID userId) {
        if (!settings.isTotpEnabled()) {
            throw new ApiExceptions.BadRequest("Authenticator app sign-in is not enabled");
        }
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiExceptions.Unauthorized("Not authenticated"));
        if (totps.findByUserIdAndEnabledTrue(userId).isPresent()) {
            throw new ApiExceptions.Conflict("An authenticator is already set up");
        }

        String secret = totp.newSecret();
        UserTotp record = totps.findById(userId).orElseGet(() -> new UserTotp(userId, null));
        record.setSecretEnc(cipher.encrypt(secret)); // overwrite any earlier unconfirmed secret
        record.setEnabled(false);
        record.setConfirmedAt(null);
        totps.save(record);

        return new MfaDtos.TotpInitResponse(secret, totp.otpauthUri(secret, user.getEmail()));
    }

    /** Confirms the pending secret, enabling the factor, and returns the first batch of backup codes. */
    @Transactional
    public List<String> confirmTotp(UUID userId, String code) {
        UserTotp record = totps.findById(userId)
                .orElseThrow(() -> new ApiExceptions.BadRequest("Start enrollment first"));
        if (record.isEnabled()) {
            throw new ApiExceptions.Conflict("An authenticator is already set up");
        }
        if (!totp.verify(cipher.decrypt(record.getSecretEnc()), code)) {
            throw new ApiExceptions.BadRequest("That code is not valid");
        }
        record.setEnabled(true);
        record.setConfirmedAt(Instant.now());
        totps.save(record);
        audit.event(AuditEventType.MFA_ENROLLED).actor(userId).detail("TOTP").log();
        return issueRecoveryCodes(userId);
    }

    /** Turns off the authenticator (and its recovery codes) after re-verifying a current code. */
    @Transactional
    public void disableTotp(UUID userId, String code) {
        UserTotp record = totps.findByUserIdAndEnabledTrue(userId)
                .orElseThrow(() -> new ApiExceptions.BadRequest("No authenticator is set up"));
        if (!totp.verify(cipher.decrypt(record.getSecretEnc()), code)) {
            throw new ApiExceptions.BadRequest("That code is not valid");
        }
        totps.delete(record);
        recoveryCodes.clear(userId);
        audit.event(AuditEventType.MFA_DISABLED).actor(userId).detail("TOTP").log();
    }

    /** Replaces the recovery codes with a fresh batch after re-verifying a current code. */
    @Transactional
    public List<String> regenerateRecoveryCodes(UUID userId, String code) {
        UserTotp record = totps.findByUserIdAndEnabledTrue(userId)
                .orElseThrow(() -> new ApiExceptions.BadRequest("No authenticator is set up"));
        if (!totp.verify(cipher.decrypt(record.getSecretEnc()), code)) {
            throw new ApiExceptions.BadRequest("That code is not valid");
        }
        return issueRecoveryCodes(userId);
    }

    private List<String> issueRecoveryCodes(UUID userId) {
        List<String> codes = recoveryCodes.regenerate(userId);
        audit.event(AuditEventType.MFA_RECOVERY_CODES_GENERATED)
                .actor(userId).detail(codes.size() + " codes").log();
        return codes;
    }
}
