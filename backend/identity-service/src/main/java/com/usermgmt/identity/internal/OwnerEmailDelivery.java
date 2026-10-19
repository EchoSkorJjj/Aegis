package com.usermgmt.identity.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** An owner notification email already sent, by the delivery id aegis-core gave it (V16). */
@Entity
@Table(name = "owner_email_deliveries")
class OwnerEmailDelivery {

    @Id
    private UUID id;

    @Column(name = "sent_at", nullable = false, updatable = false, insertable = false)
    private Instant sentAt;

    protected OwnerEmailDelivery() {
    }
}

interface OwnerEmailDeliveryRepository extends JpaRepository<OwnerEmailDelivery, UUID> {

    /**
     * Record the delivery: 1 if it is new, 0 if it was sent already. A concurrent request with the same id waits
     * here until the first one's transaction ends, then gets 0 if that one sent the email.
     */
    @Modifying
    @Query(value = "insert into owner_email_deliveries (id) values (:id) on conflict (id) do nothing",
            nativeQuery = true)
    int record(@Param("id") UUID id);
}
