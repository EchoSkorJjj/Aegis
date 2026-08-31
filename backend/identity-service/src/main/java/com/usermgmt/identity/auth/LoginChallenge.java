package com.usermgmt.identity.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A pending two-factor login: after the password succeeds, a one-time code is emailed and this row
 * holds its (hashed) value. The client presents the challenge id + code to complete the login.
 */
@Entity
@Table(name = "login_challenges")
public class LoginChallenge {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Which second factor this challenge is answered with. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MfaMethod method;

    /** SHA-256 hex of the emailed one-time code — only set for the EMAIL_OTP method. */
    @Column(name = "code_hash", length = 64)
    private String codeHash;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(nullable = false)
    private boolean consumed = false;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected LoginChallenge() {
    }

    public LoginChallenge(UUID id, UUID userId, MfaMethod method, String codeHash, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.method = method;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public MfaMethod getMethod() {
        return method;
    }

    public void setMethod(MfaMethod method) {
        this.method = method;
    }

    public void setCodeHash(String codeHash) {
        this.codeHash = codeHash;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public boolean isConsumed() {
        return consumed;
    }

    public void setConsumed(boolean consumed) {
        this.consumed = consumed;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
