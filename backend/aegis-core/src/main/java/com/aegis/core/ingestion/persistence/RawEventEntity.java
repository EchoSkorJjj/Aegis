package com.aegis.core.ingestion.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import org.springframework.data.domain.Persistable;

/**
 * An append-only raw feed event. De-duplication is enforced by a unique index on {@code dedup_key}.
 *
 * <p>Implements {@link Persistable} returning {@code isNew() == true} because rows are only ever
 * inserted (never updated), which lets Spring Data skip the pre-insert existence {@code SELECT}.
 */
@Entity
@Table(name = "raw_events")
class RawEventEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "source_id", nullable = false, length = 64)
    private String sourceId;

    @Column(name = "external_id", columnDefinition = "text")
    private String externalId;

    @Column(name = "dedup_key", nullable = false, updatable = false, length = 600)
    private String dedupKey;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "ingested_at", nullable = false, updatable = false)
    private Instant ingestedAt;

    /** Set once normalisation has consumed this event; null means "still queued". */
    @Column(name = "processed_at")
    private Instant processedAt;

    protected RawEventEntity() {
        // Required by JPA.
    }

    RawEventEntity(
            UUID id,
            String sourceId,
            String externalId,
            String dedupKey,
            String contentType,
            String payload,
            Instant occurredAt) {
        this.id = id;
        this.sourceId = sourceId;
        this.externalId = externalId;
        this.dedupKey = dedupKey;
        this.contentType = contentType;
        this.payload = payload;
        this.occurredAt = occurredAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return true;
    }

    String getDedupKey() {
        return dedupKey;
    }

    Instant getIngestedAt() {
        return ingestedAt;
    }

    String getSourceId() {
        return sourceId;
    }

    String getExternalId() {
        return externalId;
    }

    String getContentType() {
        return contentType;
    }

    String getPayload() {
        return payload;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }
}
