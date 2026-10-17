package com.aegis.core.notifications.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /** Bind parameters per lookup stay well under PostgreSQL's limit however many items an organization has. */
    private static final int KEYS_PER_QUERY = 1000;
    private static final Sort OLDEST_FIRST = Sort.by("createdAt", "id");

    @PersistenceContext
    private EntityManager entityManager;

    private final CurrentOrg currentOrg;
    private final OwnerNotificationJpaRepository notifications;

    JpaOwnerNotificationStore(CurrentOrg currentOrg, OwnerNotificationJpaRepository notifications) {
        this.currentOrg = currentOrg;
        this.notifications = notifications;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<ItemOwner, UUID> linksOf(Collection<UUID> serviceIds, Collection<String> itemKeys) {
        if (serviceIds.isEmpty() || itemKeys.isEmpty()) {
            return Map.of();
        }
        UUID org = scope();
        List<String> keys = List.copyOf(new LinkedHashSet<>(itemKeys));
        Map<ItemOwner, UUID> links = new HashMap<>();
        for (int start = 0; start < keys.size(); start += KEYS_PER_QUERY) {
            for (OwnerNotificationJpaRepository.Link link : notifications.findLinks(org, serviceIds,
                    keys.subList(start, Math.min(start + KEYS_PER_QUERY, keys.size())))) {
                links.put(new ItemOwner(link.getUserId(), link.getServiceId(), link.getItemKey()),
                        link.getChangeSignalId());
            }
        }
        return links;
    }

    @Override
    @Transactional
    public boolean addIfAbsent(OwnerNotification notification) {
        return notifications.insertIfAbsent(notification.id(), scope(), notification.userId(),
                notification.serviceId(), notification.itemKey(), notification.changeSignalId(),
                notification.itemKind().name(), notification.title(), notification.serviceName(),
                notification.score(), notification.tier().name()) == 1;
    }

    @Override
    @Transactional
    public void relink(ItemOwner notification, UUID changeSignalId) {
        notifications.relink(scope(), notification.userId(), notification.serviceId(), notification.itemKey(),
                changeSignalId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OwnerNotification> pendingEmail() {
        return notifications.findByOrgIdAndEmailStatus(scope(), EmailStatus.PENDING, OLDEST_FIRST).stream()
                .map(OwnerNotificationEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> deliveriesDue(Instant now) {
        return notifications.findDeliveriesDue(scope(), EmailStatus.SENDING, now);
    }

    @Override
    @Transactional
    public Claim claimForEmail(UUID deliveryId, Collection<UUID> ids, Instant dueAt) {
        if (ids.isEmpty()) {
            return new Claim(deliveryId, 1, List.of());
        }
        UUID org = scope();
        notifications.claim(org, ids, deliveryId, dueAt, EmailStatus.PENDING, EmailStatus.SENDING);
        return claimed(org, deliveryId);
    }

    @Override
    @Transactional
    public Claim reclaim(UUID deliveryId, Instant now, Instant dueAt, int maxAttempts) {
        UUID org = scope();
        notifications.giveUp(org, deliveryId, now, maxAttempts, EmailStatus.SENDING, EmailStatus.FAILED);
        if (notifications.retake(org, deliveryId, now, dueAt, maxAttempts, EmailStatus.SENDING) == 0) {
            return new Claim(deliveryId, 0, List.of());
        }
        return claimed(org, deliveryId);
    }

    @Override
    @Transactional
    public void recordEmail(UUID deliveryId, EmailStatus outcome, Instant at) {
        if (outcome != EmailStatus.SENT && outcome != EmailStatus.UNREACHABLE && outcome != EmailStatus.FAILED) {
            throw new IllegalArgumentException("An email's outcome is SENT, UNREACHABLE or FAILED, not " + outcome);
        }
        notifications.recordEmail(scope(), deliveryId, outcome, outcome == EmailStatus.SENT ? at : null,
                EmailStatus.SENDING);
    }

    @Override
    @Transactional
    public void retryAt(UUID deliveryId, Instant dueAt) {
        notifications.retryAt(scope(), deliveryId, dueAt, EmailStatus.SENDING);
    }

    /** The email's notifications as this transaction left them: every row carries the same try. */
    private Claim claimed(UUID org, UUID deliveryId) {
        entityManager.clear();
        List<OwnerNotificationEntity> rows = notifications.findByOrgIdAndEmailDeliveryIdAndEmailStatus(org,
                deliveryId, EmailStatus.SENDING, OLDEST_FIRST);
        return new Claim(deliveryId, rows.isEmpty() ? 0 : rows.getFirst().emailAttempts(),
                rows.stream().map(OwnerNotificationEntity::toDomain).toList());
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
