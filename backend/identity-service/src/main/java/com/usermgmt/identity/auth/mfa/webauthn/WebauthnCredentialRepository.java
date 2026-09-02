package com.usermgmt.identity.auth.mfa.webauthn;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebauthnCredentialRepository extends JpaRepository<WebauthnCredential, UUID> {

    /** A user's registered passkeys (for excludeCredentials on registration, allowCredentials on login). */
    List<WebauthnCredential> findByUserId(UUID userId);

    /** True once a user has at least one passkey — the enrollment check used to gate the factor. */
    boolean existsByUserId(UUID userId);

    /** Whether this credential is already registered (to anyone) — guards against double registration. */
    boolean existsByCredentialId(String credentialId);

    /** The stored credential presented in an assertion, looked up by its base64url id. */
    Optional<WebauthnCredential> findByCredentialId(String credentialId);

    /** A specific credential scoped to its owner — so a user can only manage their own passkey. */
    Optional<WebauthnCredential> findByIdAndUserId(UUID id, UUID userId);
}
