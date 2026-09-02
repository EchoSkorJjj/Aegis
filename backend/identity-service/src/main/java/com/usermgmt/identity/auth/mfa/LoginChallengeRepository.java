package com.usermgmt.identity.auth.mfa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface LoginChallengeRepository extends JpaRepository<LoginChallenge, UUID> {

    /** Invalidate a user's still-open challenges so only the newest emailed code stays valid. */
    @Modifying
    @Query("update LoginChallenge c set c.consumed = true where c.userId = :userId and c.consumed = false")
    int consumeActiveForUser(@Param("userId") UUID userId);
}
