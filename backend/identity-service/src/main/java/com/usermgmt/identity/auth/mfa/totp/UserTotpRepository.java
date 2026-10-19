package com.usermgmt.identity.auth.mfa.totp;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTotpRepository extends JpaRepository<UserTotp, UUID> {

    /** A user's enrolled + confirmed authenticator, if any. */
    Optional<UserTotp> findByUserIdAndEnabledTrue(UUID userId);
}
