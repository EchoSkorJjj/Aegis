package com.aegis.core.notifications;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Owner notifications, scoped to the caller's organization (V35). */
public interface OwnerNotificationStore {

    /**
     * Record that this owner is told about this item, unless they already were (the unique key decides, so this
     * holds across instances and runs). True when it was new.
     */
    boolean addIfAbsent(OwnerNotification notification);

    /** Every notification whose email is still to be sent, oldest first. */
    List<OwnerNotification> pendingEmail();

    /** Move these from {@code PENDING} to {@code SENDING}; the ones that were pending, now claimed. */
    List<OwnerNotification> claimForEmail(Collection<UUID> ids);

    /** The claimed emails' outcome: {@code SENT} (now) or {@code UNREACHABLE}. */
    void recordEmail(Collection<UUID> ids, OwnerNotification.EmailStatus outcome);

    /**
     * The claimed emails failed: back to {@code PENDING} for the next run, or {@code FAILED} once each has been
     * tried {@code maxAttempts} times.
     */
    void releaseForRetry(Collection<UUID> ids, int maxAttempts);

    /** This user's notifications, newest first; only the unread ones if {@code unreadOnly}. */
    Page<OwnerNotification> findForUser(UUID userId, boolean unreadOnly, Pageable pageable);

    long countUnread(UUID userId);

    /** Mark one of this user's notifications read; false if they have none with that id. */
    boolean markRead(UUID id, UUID userId);

    /** Mark all of this user's notifications read; how many were unread. */
    int markAllRead(UUID userId);
}
