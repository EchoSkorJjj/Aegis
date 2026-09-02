package com.usermgmt.identity.auth.mfa.webauthn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A registered WebAuthn / passkey credential. Holds a <em>public</em> key (so it is stored as-is,
 * not encrypted), plus the signature counter used to detect cloned authenticators. A user may have
 * several — one row per authenticator/passkey.
 */
@Entity
@Table(name = "webauthn_credentials")
public class WebauthnCredential {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The credential's base64url id — unique across all users; used to look the credential up. */
    @Column(name = "credential_id", nullable = false)
    private String credentialId;

    /** webauthn4j-serialised attested credential data (base64url), from which the public key is read. */
    @Column(name = "attested_credential_data", nullable = false)
    private String attestedCredentialData;

    /** Authenticator signature counter; must never go backwards (clone detection). */
    @Column(name = "sign_count", nullable = false)
    private long signCount;

    @Column(name = "aaguid")
    private String aaguid;

    /** Comma-separated transport hints reported by the client (e.g. {@code internal,hybrid}). */
    @Column(name = "transports")
    private String transports;

    /** User-friendly name for the passkey (e.g. "MacBook Touch ID"). */
    @Column(name = "label")
    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected WebauthnCredential() {
    }

    public WebauthnCredential(UUID id, UUID userId, String credentialId,
                              String attestedCredentialData, long signCount) {
        this.id = id;
        this.userId = userId;
        this.credentialId = credentialId;
        this.attestedCredentialData = attestedCredentialData;
        this.signCount = signCount;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getCredentialId() {
        return credentialId;
    }

    public String getAttestedCredentialData() {
        return attestedCredentialData;
    }

    public long getSignCount() {
        return signCount;
    }

    public void setSignCount(long signCount) {
        this.signCount = signCount;
    }

    public String getAaguid() {
        return aaguid;
    }

    public void setAaguid(String aaguid) {
        this.aaguid = aaguid;
    }

    public String getTransports() {
        return transports;
    }

    public void setTransports(String transports) {
        this.transports = transports;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }
}
