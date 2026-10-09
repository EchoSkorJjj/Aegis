package com.aegis.core.notifications.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.aegis.core.notifications.OwnerNotification;
import com.aegis.core.notifications.OwnerNotification.EmailStatus;
import com.aegis.core.notifications.OwnerNotification.ItemKind;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** An owner notification row (V35). The item's details never change; only reading and the email do. */
@Entity
@Table(name = "owner_notifications")
class OwnerNotificationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Column(name = "change_signal_id", nullable = false, updatable = false)
    private UUID changeSignalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_kind", nullable = false, updatable = false, length = 16)
    private ItemKind itemKind;

    @Column(name = "title", nullable = false, updatable = false, length = 500)
    private String title;

    @Column(name = "service_name", nullable = false, updatable = false, length = 255)
    private String serviceName;

    @Column(name = "score", nullable = false, updatable = false)
    private double score;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, updatable = false, length = 16)
    private ImpactTier tier;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "email_status", nullable = false, length = 16)
    private EmailStatus emailStatus;

    @Column(name = "email_attempts", nullable = false)
    private int emailAttempts;

    @Column(name = "emailed_at")
    private Instant emailedAt;

    protected OwnerNotificationEntity() {
    }

    OwnerNotification toDomain() {
        return new OwnerNotification(id, userId, serviceId, changeSignalId, itemKind, title, serviceName, score, tier,
                createdAt, readAt, emailStatus, emailedAt);
    }
}

interface OwnerNotificationJpaRepository extends JpaRepository<OwnerNotificationEntity, UUID> {

    /** Insert unless this owner already has one for this item: 1 if inserted, 0 if it was there. */
    @Modifying
    @Query(value = """
            insert into {h-schema}owner_notifications (id, org_id, user_id, service_id, change_signal_id, item_kind, title,
                                             service_name, score, tier, email_status)
            values (:id, :orgId, :userId, :serviceId, :changeSignalId, :itemKind, :title, :serviceName, :score, :tier,
                    'PENDING')
            on conflict (org_id, user_id, service_id, change_signal_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("orgId") UUID orgId, @Param("userId") UUID userId,
            @Param("serviceId") UUID serviceId, @Param("changeSignalId") UUID changeSignalId,
            @Param("itemKind") String itemKind, @Param("title") String title, @Param("serviceName") String serviceName,
            @Param("score") double score, @Param("tier") String tier);

    List<OwnerNotificationEntity> findByOrgIdAndEmailStatus(UUID orgId, EmailStatus emailStatus, Sort sort);

    List<OwnerNotificationEntity> findByOrgIdAndIdInAndEmailStatus(UUID orgId, Collection<UUID> ids,
            EmailStatus emailStatus);

    @Modifying
    @Query("update OwnerNotificationEntity n set n.emailStatus = :to"
            + " where n.orgId = :orgId and n.id in :ids and n.emailStatus = :from")
    int moveEmail(@Param("orgId") UUID orgId, @Param("ids") Collection<UUID> ids, @Param("from") EmailStatus from,
            @Param("to") EmailStatus to);

    @Modifying
    @Query("update OwnerNotificationEntity n set n.emailStatus = :outcome, n.emailedAt = :emailedAt"
            + " where n.orgId = :orgId and n.id in :ids and n.emailStatus = :claimed")
    int recordEmail(@Param("orgId") UUID orgId, @Param("ids") Collection<UUID> ids,
            @Param("claimed") EmailStatus claimed, @Param("outcome") EmailStatus outcome,
            @Param("emailedAt") Instant emailedAt);

    /** A failed attempt: counted, and the claim moves to {@code to} for the ones within the attempts given. */
    @Modifying
    @Query("update OwnerNotificationEntity n set n.emailAttempts = n.emailAttempts + 1, n.emailStatus = :to"
            + " where n.orgId = :orgId and n.id in :ids and n.emailStatus = :claimed"
            + " and n.emailAttempts + 1 >= :fromAttempts")
    int countFailedAttempt(@Param("orgId") UUID orgId, @Param("ids") Collection<UUID> ids,
            @Param("claimed") EmailStatus claimed, @Param("to") EmailStatus to,
            @Param("fromAttempts") int fromAttempts);

    Page<OwnerNotificationEntity> findByOrgIdAndUserId(UUID orgId, UUID userId, Pageable pageable);

    Page<OwnerNotificationEntity> findByOrgIdAndUserIdAndReadAtIsNull(UUID orgId, UUID userId, Pageable pageable);

    long countByOrgIdAndUserIdAndReadAtIsNull(UUID orgId, UUID userId);

    Optional<OwnerNotificationEntity> findByIdAndOrgIdAndUserId(UUID id, UUID orgId, UUID userId);

    @Modifying
    @Query("update OwnerNotificationEntity n set n.readAt = :at"
            + " where n.orgId = :orgId and n.userId = :userId and n.id = :id and n.readAt is null")
    int markRead(@Param("orgId") UUID orgId, @Param("userId") UUID userId, @Param("id") UUID id,
            @Param("at") Instant at);

    @Modifying
    @Query("update OwnerNotificationEntity n set n.readAt = :at"
            + " where n.orgId = :orgId and n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("orgId") UUID orgId, @Param("userId") UUID userId, @Param("at") Instant at);
}
