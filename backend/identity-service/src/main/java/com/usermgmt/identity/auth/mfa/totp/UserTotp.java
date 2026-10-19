package com.usermgmt.identity.auth.mfa.totp;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A user's enrolled authenticator (TOTP). The shared secret is stored encrypted; {@code enabled}
 * flips to true only once the user proves possession by confirming a code, so a half-finished
 * enrollment never gates login.
 */
@Entity
@Table(name = "user_totp")
public class UserTotp {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "secret_enc", nullable = false)
    private String secretEnc;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    protected UserTotp() {
    }

    public UserTotp(UUID userId, String secretEnc) {
        this.userId = userId;
        this.secretEnc = secretEnc;
        this.createdAt = Instant.now();
    }

    public UUID getUserId() {
        return userId;
    }

    public String getSecretEnc() {
        return secretEnc;
    }

    public void setSecretEnc(String secretEnc) {
        this.secretEnc = secretEnc;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }
}
