package com.usermgmt.identity.auth.mfa.webauthn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The pending WebAuthn registration challenge for a user, issued by {@code /register/begin} and
 * consumed by {@code /register/finish}. One per user (overwritten on each begin), short-lived, and
 * single-use — the server-side half of the ceremony that binds the returned attestation to a
 * challenge it actually issued.
 */
@Entity
@Table(name = "webauthn_registration")
public class WebauthnRegistration {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    /** The base64url challenge the authenticator must sign over. */
    @Column(nullable = false)
    private String challenge;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected WebauthnRegistration() {
    }

    public WebauthnRegistration(UUID userId) {
        this.userId = userId;
        this.createdAt = Instant.now();
    }

    public UUID getUserId() {
        return userId;
    }

    public String getChallenge() {
        return challenge;
    }

    public void setChallenge(String challenge) {
        this.challenge = challenge;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
