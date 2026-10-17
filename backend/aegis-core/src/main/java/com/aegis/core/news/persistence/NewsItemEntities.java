package com.aegis.core.news.persistence;

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

/** A normalised news article row, updated in place when a publisher edits the entry. */
@Entity
@Table(name = "news_items")
class NewsItemEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "source_id", nullable = false, updatable = false, length = 64)
    private String sourceId;

    @Column(name = "external_id", nullable = false, updatable = false, columnDefinition = "text")
    private String externalId;

    @Column(name = "feed_key", nullable = false, length = 64)
    private String feedKey;

    @Column(name = "feed_name", nullable = false, length = 255)
    private String feedName;

    @Column(name = "title", nullable = false, columnDefinition = "text")
    private String title;

    @Column(name = "url", columnDefinition = "text")
    private String url;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "published_at")
    private Instant publishedAt;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp(source = SourceType.DB)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NewsItemEntity() {
        // Required by JPA.
    }

    NewsItemEntity(UUID id, String sourceId, String externalId) {
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

    String getFeedKey() {
        return feedKey;
    }

    String getFeedName() {
        return feedName;
    }

    String getTitle() {
        return title;
    }

    String getUrl() {
        return url;
    }

    String getSummary() {
        return summary;
    }

    Instant getPublishedAt() {
        return publishedAt;
    }

    void apply(String feedKey, String feedName, String title, String url, String summary, Instant publishedAt) {
        this.feedKey = feedKey;
        this.feedName = feedName;
        this.title = title;
        this.url = url;
        this.summary = summary;
        this.publishedAt = publishedAt;
    }
}

/**
 * A CVE identifier mentioned by an article. A plain row keyed by {@code news_item_id} rather than a
 * JPA association, matching how the rest of this schema models relationships.
 */
@Entity
@Table(name = "news_item_cve_references")
class NewsItemCveReferenceEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "news_item_id", nullable = false, updatable = false)
    private UUID newsItemId;

    @Column(name = "cve_id", nullable = false, length = 32)
    private String cveId;

    protected NewsItemCveReferenceEntity() {
        // Required by JPA.
    }

    NewsItemCveReferenceEntity(UUID id, UUID newsItemId, String cveId) {
        this.id = id;
        this.newsItemId = newsItemId;
        this.cveId = cveId;
    }

    /**
     * These rows are only ever inserted — an edit deletes the article's references and writes fresh
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

    UUID getNewsItemId() {
        return newsItemId;
    }

    String getCveId() {
        return cveId;
    }
}
