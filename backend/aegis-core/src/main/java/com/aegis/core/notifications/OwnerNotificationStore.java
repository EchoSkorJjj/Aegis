package com.aegis.core.notifications;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Owner notifications, scoped to the caller's organization (V35). */
public interface OwnerNotificationStore {

    /** One owner's notification about one item in one service: the unique key. */
    record ItemOwner(UUID userId, UUID serviceId, String itemKey) {
    }

    /** Of these items in these services, the ones already notified, each with the change its page shows. */
    Map<ItemOwner, UUID> linksOf(Collection<UUID> serviceIds, Collection<String> itemKeys);

    /**
     * Record that this owner is told about this item, unless they already were (the unique key decides, so this
     * holds across instances and runs). True when it was new.
     */
    boolean addIfAbsent(OwnerNotification notification);

    /** Point a notification at its item's current change: which feed's advisory represents a CVE can change. */
    void relink(ItemOwner notification, UUID changeSignalId);

    /** Every notification not yet in an email, oldest first. */
    List<OwnerNotification> pendingEmail();

    /** The emails claimed earlier and still unconfirmed whose next try is due at {@code now}. */
    List<UUID> deliveriesDue(Instant now);

    /** An email claimed for one try: which try it is (1 for the first) and its notifications, none if not claimed. */
    record Claim(UUID deliveryId, int attempt, List<OwnerNotification> notifications) {
    }

    /**
     * Claim these {@code PENDING} notifications into the email {@code deliveryId}, its first try, unconfirmed until
     * {@code dueAt}: the ones this call claimed, never one another run claimed.
     */
    Claim claimForEmail(UUID deliveryId, Collection<UUID> ids, Instant dueAt);

    /**
     * Take a due email for another try, unconfirmed until {@code dueAt}: none if another run took it first, or if it
     * has been tried {@code maxAttempts} times already (it is then {@code FAILED}).
     */
    Claim reclaim(UUID deliveryId, Instant now, Instant dueAt, int maxAttempts);

    /** The email's outcome: {@code SENT} (now), {@code UNREACHABLE} or {@code FAILED}. */
    void recordEmail(UUID deliveryId, OwnerNotification.EmailStatus outcome, Instant at);

    /** The email failed and may work later: tried again, under the same delivery id, from {@code dueAt}. */
    void retryAt(UUID deliveryId, Instant dueAt);

    /** This user's notifications, newest first; only the unread ones if {@code unreadOnly}. */
    Page<OwnerNotification> findForUser(UUID userId, boolean unreadOnly, Pageable pageable);

    long countUnread(UUID userId);

    /** Mark one of this user's notifications read; false if they have none with that id. */
    boolean markRead(UUID id, UUID userId);

    /** Mark all of this user's notifications read; how many were unread. */
    int markAllRead(UUID userId);
}
