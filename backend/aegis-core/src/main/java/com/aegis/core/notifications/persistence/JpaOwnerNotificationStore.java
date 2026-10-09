package com.aegis.core.notifications.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.aegis.core.notifications.OwnerNotification;
import com.aegis.core.notifications.OwnerNotification.EmailStatus;
import com.aegis.core.notifications.OwnerNotificationStore;
import com.aegis.core.platform.tenant.CurrentOrg;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA adapter for {@link OwnerNotificationStore}. Every method resolves the caller's org through {@link #scope()}
 * — which also binds it on the database session for the row-level-security backstop (V35) — then touches only
 * that org's rows and stamps it on inserts.
 */
@Repository
class JpaOwnerNotificationStore implements OwnerNotificationStore {

    @PersistenceContext
    private EntityManager entityManager;

    private final CurrentOrg currentOrg;
    private final OwnerNotificationJpaRepository notifications;

    JpaOwnerNotificationStore(CurrentOrg currentOrg, OwnerNotificationJpaRepository notifications) {
        this.currentOrg = currentOrg;
        this.notifications = notifications;
    }

    @Override
    @Transactional
    public boolean addIfAbsent(OwnerNotification notification) {
        return notifications.insertIfAbsent(notification.id(), scope(), notification.userId(),
                notification.serviceId(), notification.changeSignalId(), notification.itemKind().name(),
                notification.title(), notification.serviceName(), notification.score(),
                notification.tier().name()) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OwnerNotification> pendingEmail() {
        return notifications.findByOrgIdAndEmailStatus(scope(), EmailStatus.PENDING,
                        Sort.by("createdAt", "id")).stream()
                .map(OwnerNotificationEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public List<OwnerNotification> claimForEmail(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        UUID org = scope();
        notifications.moveEmail(org, ids, EmailStatus.PENDING, EmailStatus.SENDING);
        entityManager.clear();
        return notifications.findByOrgIdAndIdInAndEmailStatus(org, ids, EmailStatus.SENDING).stream()
                .map(OwnerNotificationEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public void recordEmail(Collection<UUID> ids, EmailStatus outcome) {
        if (outcome != EmailStatus.SENT && outcome != EmailStatus.UNREACHABLE) {
            throw new IllegalArgumentException("An email's outcome is SENT or UNREACHABLE, not " + outcome);
        }
        if (!ids.isEmpty()) {
            notifications.recordEmail(scope(), ids, EmailStatus.SENDING, outcome,
                    outcome == EmailStatus.SENT ? Instant.now() : null);
        }
    }

    @Override
    @Transactional
    public void releaseForRetry(Collection<UUID> ids, int maxAttempts) {
        if (!ids.isEmpty()) {
            UUID org = scope();
            // The ones on their last attempt give up; the rest wait for the next run.
            notifications.countFailedAttempt(org, ids, EmailStatus.SENDING, EmailStatus.FAILED, maxAttempts);
            notifications.countFailedAttempt(org, ids, EmailStatus.SENDING, EmailStatus.PENDING, Integer.MIN_VALUE);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OwnerNotification> findForUser(UUID userId, boolean unreadOnly, Pageable pageable) {
        UUID org = scope();
        return (unreadOnly
                ? notifications.findByOrgIdAndUserIdAndReadAtIsNull(org, userId, pageable)
                : notifications.findByOrgIdAndUserId(org, userId, pageable))
                .map(OwnerNotificationEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnread(UUID userId) {
        return notifications.countByOrgIdAndUserIdAndReadAtIsNull(scope(), userId);
    }

    @Override
    @Transactional
    public boolean markRead(UUID id, UUID userId) {
        UUID org = scope();
        if (notifications.findByIdAndOrgIdAndUserId(id, org, userId).isEmpty()) {
            return false;
        }
        notifications.markRead(org, userId, id, Instant.now());
        return true;
    }

    @Override
    @Transactional
    public int markAllRead(UUID userId) {
        return notifications.markAllRead(scope(), userId, Instant.now());
    }

    private UUID scope() {
        UUID org = currentOrg.require();
        entityManager.createNativeQuery("select set_config('aegis.current_org', :org, true)")
                .setParameter("org", org.toString())
                .getSingleResult();
        return org;
    }
}
