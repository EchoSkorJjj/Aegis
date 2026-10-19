package com.usermgmt.identity.auth.mfa.webauthn;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Pending registration challenges, keyed by user (one in flight at a time). */
public interface WebauthnRegistrationRepository extends JpaRepository<WebauthnRegistration, UUID> {
}
