package com.usermgmt.identity.audit;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A single, immutable entry in the security audit trail. Rows are only ever inserted — the
 * table is append-only (enforced by a database trigger; see V5 migration) — so this entity
 * exposes getters only and has no setters.
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private AuditEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AuditOutcome outcome;

    /** The user who performed the action, if known (null for anonymous/pre-auth attempts). */
    @Column(name = "actor_user_id")
    private UUID actorUserId;

    /** Denormalised actor email — kept even when there is no user id (e.g. login for an unknown address). */
    @Column(name = "actor_email", length = 320)
    private String actorEmail;

    /** The user the action was performed on, if different from the actor (e.g. an admin role change). */
    @Column(name = "target_user_id")
    private UUID targetUserId;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    /** Short human-readable context, e.g. "roles: [USER] -> [USER, ADMIN]". */
    @Column(columnDefinition = "text")
    private String detail;

    protected AuditEvent() {
    }

    public AuditEvent(UUID id, Instant occurredAt, AuditEventType eventType, AuditOutcome outcome,
                      UUID actorUserId, String actorEmail, UUID targetUserId,
                      String ipAddress, String userAgent, String detail) {
        this.id = id;
        this.occurredAt = occurredAt;
        this.eventType = eventType;
        this.outcome = outcome;
        this.actorUserId = actorUserId;
        this.actorEmail = actorEmail;
        this.targetUserId = targetUserId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.detail = detail;
    }

    public UUID getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public AuditEventType getEventType() {
        return eventType;
    }

    public AuditOutcome getOutcome() {
        return outcome;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getActorEmail() {
        return actorEmail;
    }

    public UUID getTargetUserId() {
        return targetUserId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getDetail() {
        return detail;
    }
}
