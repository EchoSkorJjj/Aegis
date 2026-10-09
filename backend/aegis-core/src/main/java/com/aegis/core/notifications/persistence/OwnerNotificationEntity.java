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

/**
 * An owner notification row (V35). The item's details never change; only its page (the change it links to),
 * reading and the email do.
 */
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

    @Column(name = "item_key", nullable = false, updatable = false, length = 512)
    private String itemKey;

    @Column(name = "change_signal_id", nullable = false)
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

    @Column(name = "email_delivery_id")
    private UUID emailDeliveryId;

    @Column(name = "email_attempts", nullable = false)
    private int emailAttempts;

    @Column(name = "email_due_at")
    private Instant emailDueAt;

    @Column(name = "emailed_at")
    private Instant emailedAt;

    protected OwnerNotificationEntity() {
    }

    int emailAttempts() {
        return emailAttempts;
    }

    OwnerNotification toDomain() {
        return new OwnerNotification(id, userId, serviceId, itemKey, changeSignalId, itemKind, title, serviceName,
                score, tier, createdAt, readAt, emailStatus, emailedAt);
    }
}

interface OwnerNotificationJpaRepository extends JpaRepository<OwnerNotificationEntity, UUID> {

    /** A notification's key and the change its page shows. */
    interface Link {
        UUID getUserId();

        UUID getServiceId();

        String getItemKey();

        UUID getChangeSignalId();
    }

    @Query("select n.userId as userId, n.serviceId as serviceId, n.itemKey as itemKey,"
            + " n.changeSignalId as changeSignalId from OwnerNotificationEntity n"
            + " where n.orgId = :orgId and n.serviceId in :serviceIds and n.itemKey in :itemKeys")
    List<Link> findLinks(@Param("orgId") UUID orgId, @Param("serviceIds") Collection<UUID> serviceIds,
            @Param("itemKeys") Collection<String> itemKeys);

    /** Insert unless this owner already has one for this item: 1 if inserted, 0 if it was there. */
    @Modifying
    @Query(value = """
            insert into {h-schema}owner_notifications (id, org_id, user_id, service_id, item_key, change_signal_id,
                                             item_kind, title, service_name, score, tier, email_status)
            values (:id, :orgId, :userId, :serviceId, :itemKey, :changeSignalId, :itemKind, :title, :serviceName,
                    :score, :tier, 'PENDING')
            on conflict (org_id, user_id, service_id, item_key) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("orgId") UUID orgId, @Param("userId") UUID userId,
            @Param("serviceId") UUID serviceId, @Param("itemKey") String itemKey,
            @Param("changeSignalId") UUID changeSignalId, @Param("itemKind") String itemKind,
            @Param("title") String title, @Param("serviceName") String serviceName, @Param("score") double score,
            @Param("tier") String tier);

    @Modifying
    @Query("update OwnerNotificationEntity n set n.changeSignalId = :changeSignalId where n.orgId = :orgId"
            + " and n.userId = :userId and n.serviceId = :serviceId and n.itemKey = :itemKey")
    int relink(@Param("orgId") UUID orgId, @Param("userId") UUID userId, @Param("serviceId") UUID serviceId,
            @Param("itemKey") String itemKey, @Param("changeSignalId") UUID changeSignalId);

    List<OwnerNotificationEntity> findByOrgIdAndEmailStatus(UUID orgId, EmailStatus emailStatus, Sort sort);

    @Query("select distinct n.emailDeliveryId from OwnerNotificationEntity n"
            + " where n.orgId = :orgId and n.emailStatus = :sending and n.emailDueAt <= :now")
    List<UUID> findDeliveriesDue(@Param("orgId") UUID orgId, @Param("sending") EmailStatus sending,
            @Param("now") Instant now);

    List<OwnerNotificationEntity> findByOrgIdAndEmailDeliveryIdAndEmailStatus(UUID orgId, UUID emailDeliveryId,
            EmailStatus emailStatus, Sort sort);

    /** Claim pending notifications into a new email, its first try: only the ones still pending move. */
    @Modifying
    @Query("update OwnerNotificationEntity n set n.emailStatus = :sending, n.emailDeliveryId = :deliveryId,"
            + " n.emailAttempts = 1, n.emailDueAt = :dueAt"
            + " where n.orgId = :orgId and n.id in :ids and n.emailStatus = :pending")
    int claim(@Param("orgId") UUID orgId, @Param("ids") Collection<UUID> ids, @Param("deliveryId") UUID deliveryId,
            @Param("dueAt") Instant dueAt, @Param("pending") EmailStatus pending,
            @Param("sending") EmailStatus sending);

    /** Another try of a due email: only one run moves its due time, so only one run tries it. */
    @Modifying
    @Query("update OwnerNotificationEntity n set n.emailAttempts = n.emailAttempts + 1, n.emailDueAt = :dueAt"
            + " where n.orgId = :orgId and n.emailDeliveryId = :deliveryId and n.emailStatus = :sending"
            + " and n.emailDueAt <= :now and n.emailAttempts < :maxAttempts")
    int retake(@Param("orgId") UUID orgId, @Param("deliveryId") UUID deliveryId, @Param("now") Instant now,
            @Param("dueAt") Instant dueAt, @Param("maxAttempts") int maxAttempts,
            @Param("sending") EmailStatus sending);

    /** A due email already tried as often as allowed gives up. */
    @Modifying
    @Query("update OwnerNotificationEntity n set n.emailStatus = :failed, n.emailDueAt = null"
            + " where n.orgId = :orgId and n.emailDeliveryId = :deliveryId and n.emailStatus = :sending"
            + " and n.emailDueAt <= :now and n.emailAttempts >= :maxAttempts")
    int giveUp(@Param("orgId") UUID orgId, @Param("deliveryId") UUID deliveryId, @Param("now") Instant now,
            @Param("maxAttempts") int maxAttempts, @Param("sending") EmailStatus sending,
            @Param("failed") EmailStatus failed);

    @Modifying
    @Query("update OwnerNotificationEntity n set n.emailStatus = :outcome, n.emailedAt = :emailedAt,"
            + " n.emailDueAt = null"
            + " where n.orgId = :orgId and n.emailDeliveryId = :deliveryId and n.emailStatus = :sending")
    int recordEmail(@Param("orgId") UUID orgId, @Param("deliveryId") UUID deliveryId,
            @Param("outcome") EmailStatus outcome, @Param("emailedAt") Instant emailedAt,
            @Param("sending") EmailStatus sending);

    @Modifying
    @Query("update OwnerNotificationEntity n set n.emailDueAt = :dueAt"
            + " where n.orgId = :orgId and n.emailDeliveryId = :deliveryId and n.emailStatus = :sending")
    int retryAt(@Param("orgId") UUID orgId, @Param("deliveryId") UUID deliveryId, @Param("dueAt") Instant dueAt,
            @Param("sending") EmailStatus sending);

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
