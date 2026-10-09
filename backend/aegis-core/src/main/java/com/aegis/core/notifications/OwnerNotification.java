package com.aegis.core.notifications;

import java.time.Instant;
import java.util.UUID;

import com.aegis.core.scoring.ScoringDtos.ImpactTier;

/**
 * One owner told about one item: a material vulnerability or vendor outage ({@code changeSignalId}) in one of
 * the services they own. The title, service name, score and tier are as they were when the item was first seen;
 * the item itself is live ({@code /findings/<changeSignalId>}). {@code readAt} is null until they read it.
 */
public record OwnerNotification(
        UUID id,
        UUID userId,
        UUID serviceId,
        UUID changeSignalId,
        ItemKind itemKind,
        String title,
        String serviceName,
        double score,
        ImpactTier tier,
        Instant createdAt,
        Instant readAt,
        EmailStatus emailStatus,
        Instant emailedAt) {

    public enum ItemKind { VULNERABILITY, VENDOR_OUTAGE }

    /**
     * Where its email stands. Only {@code PENDING} is ever sent, and it is claimed ({@code SENDING}) first, so an
     * owner is never emailed twice about the same item — even if the job stops halfway.
     */
    public enum EmailStatus {
        PENDING,
        SENDING,
        SENT,
        /** identity-service won't email that member: they left the organization, or can't be emailed. */
        UNREACHABLE,
        /** Gave up after {@code OwnerNotifier.MAX_EMAIL_ATTEMPTS} errors. */
        FAILED
    }

    /** Its item's page in the app. */
    public String path() {
        return "/findings/" + changeSignalId;
    }
}
