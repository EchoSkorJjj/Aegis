package com.aegis.core.signals.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.aegis.core.signals.ChangeSignal.SignalType;
import org.hibernate.annotations.Immutable;

/**
 * The read-only mapping onto the {@code change_signals} database view. {@link Immutable} tells
 * Hibernate this is never the target of an insert/update/delete — the view has no writer, and each
 * source's own connector owns writing its underlying table.
 */
@Entity
@Immutable
@Table(name = "change_signals")
class ChangeSignalEntity {

    // No column here is marked non-null: Postgres reports every column of a view as nullable in
    // information_schema regardless of the underlying tables' constraints, so declaring @Column
    // nullable = false would fail Hibernate's schema validation against this entity's view.
    @Id
    @Column(name = "id")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", length = 32)
    private SignalType signalType;

    @Column(name = "source_id", length = 64)
    private String sourceId;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "title")
    private String title;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "severity")
    private String severity;

    @Column(name = "url")
    private String url;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected ChangeSignalEntity() {
        // Required by JPA.
    }

    UUID getId() {
        return id;
    }

    SignalType getSignalType() {
        return signalType;
    }

    String getSourceId() {
        return sourceId;
    }

    String getExternalId() {
        return externalId;
    }

    String getTitle() {
        return title;
    }

    String getSummary() {
        return summary;
    }

    String getSeverity() {
        return severity;
    }

    String getUrl() {
        return url;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
