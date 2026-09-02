package com.usermgmt.identity.auth.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AuthTokenRepository extends JpaRepository<AuthToken, UUID> {

    Optional<AuthToken> findByTokenHash(String tokenHash);

    /** Marks any earlier unused tokens of this type as used, so only the newest is valid. */
    @Modifying
    @Query("update AuthToken t set t.used = true where t.userId = :userId and t.type = :type and t.used = false")
    int invalidatePrevious(@Param("userId") UUID userId, @Param("type") TokenType type);
}
