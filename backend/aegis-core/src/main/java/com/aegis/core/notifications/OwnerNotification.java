package com.aegis.core.notifications;

import java.time.Instant;
import java.util.UUID;

import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * One owner told about one item: a material vulnerability or vendor outage in one of the services they own.
 * {@code itemKey} is what makes it the same item from run to run (see {@link OwnerNotifier#itemKey}), and
 * {@code changeSignalId} its page ({@code /findings/<changeSignalId>}), kept current. The title, service name,
 * score and tier are as they were when the item was first seen. {@code readAt} is null until they read it.
 */
public record OwnerNotification(
        UUID id,
        UUID userId,
        UUID serviceId,
        @JsonIgnore String itemKey,
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
     * Where its email stands. A notification is claimed into an email ({@code SENDING}, under a delivery id) before
     * it is sent; until identity-service confirms it, the same email is asked for again under the same id, which
     * identity-service sends only once. So an owner is never emailed twice about the same item — even if a call
     * times out or the job stops halfway.
     */
    public enum EmailStatus {
        PENDING,
        SENDING,
        SENT,
        /** identity-service won't email that member: they left the organization, or can't be emailed. */
        UNREACHABLE,
        /** Gave up: identity-service refused the email, or it failed {@code OwnerNotifier.MAX_EMAIL_ATTEMPTS} times. */
        FAILED
    }

    /** Its item's page in the app. */
    public String path() {
        return "/findings/" + changeSignalId;
    }
}
