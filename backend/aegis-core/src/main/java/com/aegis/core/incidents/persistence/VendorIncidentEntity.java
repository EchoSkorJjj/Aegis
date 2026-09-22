package com.aegis.core.incidents.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A normalised vendor incident row. Mutable by design: a vendor edits an incident as it investigates,
 * so the same {@code (source_id, external_id)} row is updated rather than duplicated.
 */
@Entity
@Table(name = "vendor_incidents")
class VendorIncidentEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "source_id", nullable = false, updatable = false, length = 64)
    private String sourceId;

    @Column(name = "external_id", nullable = false, updatable = false, columnDefinition = "text")
    private String externalId;

    @Column(name = "vendor_key", nullable = false, length = 64)
    private String vendorKey;

    @Column(name = "vendor_name", nullable = false, length = 255)
    private String vendorName;

    @Column(name = "title", nullable = false, columnDefinition = "text")
    private String title;

    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "impact", length = 32)
    private String impact;

    @Column(name = "url", columnDefinition = "text")
    private String url;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "last_update_at")
    private Instant lastUpdateAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp(source = SourceType.DB)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VendorIncidentEntity() {
        // Required by JPA.
    }

    VendorIncidentEntity(UUID id, String sourceId, String externalId) {
        this.id = id;
        this.sourceId = sourceId;
        this.externalId = externalId;
    }

    UUID getId() {
        return id;
    }

    String getSourceId() {
        return sourceId;
    }

    String getExternalId() {
        return externalId;
    }

    String getVendorKey() {
        return vendorKey;
    }

    String getVendorName() {
        return vendorName;
    }

    String getTitle() {
        return title;
    }

    String getStatus() {
        return status;
    }

    String getImpact() {
        return impact;
    }

    String getUrl() {
        return url;
    }

    String getSummary() {
        return summary;
    }

    Instant getStartedAt() {
        return startedAt;
    }

    Instant getLastUpdateAt() {
        return lastUpdateAt;
    }

    Instant getResolvedAt() {
        return resolvedAt;
    }

    void apply(
            String vendorKey,
            String vendorName,
            String title,
            String status,
            String impact,
            String url,
            String summary,
            Instant startedAt,
            Instant lastUpdateAt,
            Instant resolvedAt) {
        this.vendorKey = vendorKey;
        this.vendorName = vendorName;
        this.title = title;
        this.status = status;
        this.impact = impact;
        this.url = url;
        this.summary = summary;
        this.startedAt = startedAt;
        this.lastUpdateAt = lastUpdateAt;
        this.resolvedAt = resolvedAt;
    }
}
