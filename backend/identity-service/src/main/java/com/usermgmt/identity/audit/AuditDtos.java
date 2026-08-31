package com.usermgmt.identity.audit;

import java.time.Instant;
import java.util.UUID;

public final class AuditDtos {

    private AuditDtos() {
    }

    /** A single audit entry as returned by the admin API. */
    public record AuditEventResponse(
            UUID id,
            Instant occurredAt,
            AuditEventType eventType,
            AuditOutcome outcome,
            UUID actorUserId,
            String actorEmail,
            UUID targetUserId,
            String ipAddress,
            String userAgent,
            String detail) {

        public static AuditEventResponse from(AuditEvent e) {
            return new AuditEventResponse(
                    e.getId(),
                    e.getOccurredAt(),
                    e.getEventType(),
                    e.getOutcome(),
                    e.getActorUserId(),
                    e.getActorEmail(),
                    e.getTargetUserId(),
                    e.getIpAddress(),
                    e.getUserAgent(),
                    e.getDetail());
        }
    }
}
