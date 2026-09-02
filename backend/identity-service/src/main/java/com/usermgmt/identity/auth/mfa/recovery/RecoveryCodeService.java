package com.usermgmt.identity.auth.mfa.recovery;

import com.usermgmt.identity.common.Tokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Single-use MFA backup codes. Codes are high-entropy and stored hashed (SHA-256 of the normalised
 * value), so input is tolerant of case and the display hyphen. The plaintext is returned once.
 */
@Service
public class RecoveryCodeService {

    // Crockford-ish alphabet without visually ambiguous characters (0/o, 1/l/i).
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final int CODE_LENGTH = 10;

    private final MfaRecoveryCodeRepository repository;
    private final int count;
    private final SecureRandom random = new SecureRandom();

    public RecoveryCodeService(MfaRecoveryCodeRepository repository,
                               @Value("${identity.mfa.recovery-codes.count:10}") int count) {
        this.repository = repository;
        this.count = count;
    }

    /** Replaces any existing codes with a fresh batch; returns the plaintext codes (shown once). */
    @Transactional
    public List<String> regenerate(UUID userId) {
        repository.deleteAllForUser(userId);
        List<String> codes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String code = newCode();
            repository.save(new MfaRecoveryCode(UUID.randomUUID(), userId, hash(code)));
            codes.add(code);
        }
        return codes;
    }

    /** Consumes a matching unused code. Safe (returns false) for users with no codes. */
    @Transactional
    public boolean consume(UUID userId, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        return repository.findByUserIdAndCodeHashAndUsedFalse(userId, hash(code))
                .map(recoveryCode -> {
                    recoveryCode.setUsed(true);
                    repository.save(recoveryCode);
                    return true;
                })
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public long remaining(UUID userId) {
        return repository.countByUserIdAndUsedFalse(userId);
    }

    @Transactional
    public void clear(UUID userId) {
        repository.deleteAllForUser(userId);
    }

    private String newCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH + 1);
        for (int i = 0; i < CODE_LENGTH; i++) {
            if (i == CODE_LENGTH / 2) {
                code.append('-');
            }
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    private static String hash(String code) {
        return Tokens.sha256Hex(normalize(code));
    }

    private static String normalize(String code) {
        return code.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
