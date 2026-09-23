package com.aegis.core.advisories.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.domain.Persistable;

/**
 * A normalised advisory row. Unlike {@code raw_events} these are mutable: a feed revises an advisory
 * (rescored CVSS, re-scoped ranges) and the same {@code (source_id, external_id)} row is updated.
 */
@Entity
@Table(name = "advisories")
class AdvisoryEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "source_id", nullable = false, updatable = false, length = 64)
    private String sourceId;

    @Column(name = "external_id", nullable = false, updatable = false, length = 64)
    private String externalId;

    @Column(name = "canonical_id", nullable = false, length = 64)
    private String canonicalId;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_modified_at")
    private Instant lastModifiedAt;

    @Column(name = "cvss_version", length = 8)
    private String cvssVersion;

    @Column(name = "cvss_vector", length = 255)
    private String cvssVector;

    @Column(name = "cvss_base_score", precision = 4, scale = 1)
    private BigDecimal cvssBaseScore;

    @Column(name = "cvss_severity", length = 16)
    private String cvssSeverity;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp(source = SourceType.DB)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AdvisoryEntity() {
        // Required by JPA.
    }

    AdvisoryEntity(UUID id, String sourceId, String externalId) {
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

    String getCanonicalId() {
        return canonicalId;
    }

    String getDescription() {
        return description;
    }

    Instant getPublishedAt() {
        return publishedAt;
    }

    Instant getLastModifiedAt() {
        return lastModifiedAt;
    }

    String getCvssVersion() {
        return cvssVersion;
    }

    String getCvssVector() {
        return cvssVector;
    }

    BigDecimal getCvssBaseScore() {
        return cvssBaseScore;
    }

    String getCvssSeverity() {
        return cvssSeverity;
    }

    void apply(
            String canonicalId,
            String description,
            Instant publishedAt,
            Instant lastModifiedAt,
            String cvssVersion,
            String cvssVector,
            BigDecimal cvssBaseScore,
            String cvssSeverity) {
        this.canonicalId = canonicalId;
        this.description = description;
        this.publishedAt = publishedAt;
        this.lastModifiedAt = lastModifiedAt;
        this.cvssVersion = cvssVersion;
        this.cvssVector = cvssVector;
        this.cvssBaseScore = cvssBaseScore;
        this.cvssSeverity = cvssSeverity;
    }
}

/**
 * One affected (vendor, product, version-range) tuple. Held as a plain row keyed by
 * {@code advisory_id} rather than a JPA association — the same choice the inventory module makes for
 * its relationship rows, and it keeps the wholesale replace-on-revision cheap.
 */
@Entity
@Table(name = "advisory_affected_products")
class AffectedProductEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "advisory_id", nullable = false, updatable = false)
    private UUID advisoryId;

    @Column(name = "cpe", columnDefinition = "text")
    private String cpe;

    @Column(name = "part", length = 8)
    private String part;

    @Column(name = "vendor", length = 255)
    private String vendor;

    @Column(name = "product", length = 255)
    private String product;

    @Column(name = "ecosystem", length = 64)
    private String ecosystem;

    @Column(name = "package_name", length = 255)
    private String packageName;

    @Column(name = "version", length = 128)
    private String version;

    @Column(name = "version_start_including", length = 128)
    private String versionStartIncluding;

    @Column(name = "version_start_excluding", length = 128)
    private String versionStartExcluding;

    @Column(name = "version_end_including", length = 128)
    private String versionEndIncluding;

    @Column(name = "version_end_excluding", length = 128)
    private String versionEndExcluding;

    protected AffectedProductEntity() {
        // Required by JPA.
    }

    AffectedProductEntity(
            UUID id,
            UUID advisoryId,
            String cpe,
            String part,
            String vendor,
            String product,
            String ecosystem,
            String packageName,
            String version,
            String versionStartIncluding,
            String versionStartExcluding,
            String versionEndIncluding,
            String versionEndExcluding) {
        this.id = id;
        this.advisoryId = advisoryId;
        this.cpe = cpe;
        this.part = part;
        this.vendor = vendor;
        this.product = product;
        this.ecosystem = ecosystem;
        this.packageName = packageName;
        this.version = version;
        this.versionStartIncluding = versionStartIncluding;
        this.versionStartExcluding = versionStartExcluding;
        this.versionEndIncluding = versionEndIncluding;
        this.versionEndExcluding = versionEndExcluding;
    }

    /**
     * These rows are only ever inserted — a revision deletes the advisory's tuples and writes fresh
     * ones — so declaring them new lets Spring Data skip a pre-insert {@code SELECT} per row.
     */
    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return true;
    }

    UUID getAdvisoryId() {
        return advisoryId;
    }

    String getCpe() {
        return cpe;
    }

    String getPart() {
        return part;
    }

    String getVendor() {
        return vendor;
    }

    String getProduct() {
        return product;
    }

    String getEcosystem() {
        return ecosystem;
    }

    String getPackageName() {
        return packageName;
    }

    String getVersion() {
        return version;
    }

    String getVersionStartIncluding() {
        return versionStartIncluding;
    }

    String getVersionStartExcluding() {
        return versionStartExcluding;
    }

    String getVersionEndIncluding() {
        return versionEndIncluding;
    }

    String getVersionEndExcluding() {
        return versionEndExcluding;
    }
}
