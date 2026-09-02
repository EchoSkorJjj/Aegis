package com.usermgmt.identity.auth.mfa.webauthn;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Pending registration challenges, keyed by user (one in flight at a time). */
public interface WebauthnRegistrationRepository extends JpaRepository<WebauthnRegistration, UUID> {
}
