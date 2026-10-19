package com.aegis.core.actions.tracker.jira.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;

import com.aegis.core.actions.tracker.jira.JiraConnectionStore.Connection;
import com.aegis.core.actions.tracker.jira.JiraConnectionStore.SealedTokens;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** An organization's Jira connection (V24). The tokens are sealed; this row never holds them in the clear. */
@Entity
@Table(name = "jira_connections")
class JiraConnectionEntity {

    @Id
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "cloud_id", nullable = false, length = 64)
    private String cloudId;

    @Column(name = "site_name", nullable = false, length = 255)
    private String siteName;

    @Column(name = "site_url", nullable = false, length = 400)
    private String siteUrl;

    @Column(name = "project_key", length = 255)
    private String projectKey;

    @Column(name = "issue_type", length = 255)
    private String issueType;

    @Column(name = "access_token", nullable = false, columnDefinition = "text")
    private String accessToken;

    @Column(name = "access_expires_at", nullable = false)
    private Instant accessExpiresAt;

    @Column(name = "refresh_token", nullable = false, columnDefinition = "text")
    private String refreshToken;

    @Column(name = "connected_by", nullable = false)
    private UUID connectedBy;

    /** When the current site and tokens were granted: a reconnection is a new grant. */
    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;

    @UpdateTimestamp(source = SourceType.DB)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected JiraConnectionEntity() {
    }

    JiraConnectionEntity(UUID orgId) {
        this.orgId = orgId;
    }

    /** A (re)connection: a new site and tokens, by this person; where tickets go is chosen afresh. */
    void connect(String cloudId, String siteName, String siteUrl, SealedTokens tokens, UUID connectedBy) {
        site(cloudId, siteName, siteUrl);
        this.projectKey = null;
        this.issueType = null;
        tokens(tokens);
        this.connectedBy = connectedBy;
        this.connectedAt = Instant.now();
    }

    void site(String cloudId, String siteName, String siteUrl) {
        this.cloudId = cloudId;
        this.siteName = siteName;
        this.siteUrl = siteUrl;
    }

    void destination(String projectKey, String issueType) {
        this.projectKey = projectKey;
        this.issueType = issueType;
    }

    void tokens(SealedTokens tokens) {
        this.accessToken = tokens.accessToken();
        this.accessExpiresAt = tokens.accessExpiresAt();
        this.refreshToken = tokens.refreshToken();
    }

    SealedTokens tokens() {
        return new SealedTokens(accessToken, accessExpiresAt, refreshToken);
    }

    Connection toDomain() {
        return new Connection(cloudId, siteName, siteUrl, projectKey, issueType, connectedBy, connectedAt);
    }
}

/** A connection in progress, under the SHA-256 of the state it handed Atlassian (V24). */
@Entity
@Table(name = "jira_connect_states")
class JiraConnectStateEntity {

    @Id
    @Column(name = "state_hash", nullable = false, updatable = false, length = 64)
    private String stateHash;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    protected JiraConnectStateEntity() {
    }

    JiraConnectStateEntity(String stateHash, UUID orgId, UUID userId, Instant expiresAt) {
        this.stateHash = stateHash;
        this.orgId = orgId;
        this.userId = userId;
        this.expiresAt = expiresAt;
    }

    UUID userId() {
        return userId;
    }

    Instant expiresAt() {
        return expiresAt;
    }
}

interface JiraConnectionJpaRepository extends JpaRepository<JiraConnectionEntity, UUID> {

    /** The organization's connection, locked until the transaction ends: token renewal happens one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from JiraConnectionEntity c where c.orgId = :orgId")
    Optional<JiraConnectionEntity> lockByOrgId(@Param("orgId") UUID orgId);

    @Modifying
    @Query("delete from JiraConnectionEntity c where c.orgId = :orgId")
    int deleteByOrg(@Param("orgId") UUID orgId);
}

interface JiraConnectStateJpaRepository extends JpaRepository<JiraConnectStateEntity, String> {

    Optional<JiraConnectStateEntity> findByStateHashAndOrgId(String stateHash, UUID orgId);

    /** A bulk delete, so two completions racing on one state cannot both see it removed. */
    @Modifying
    @Query("delete from JiraConnectStateEntity s where s.stateHash = :stateHash and s.orgId = :orgId")
    int deleteState(@Param("stateHash") String stateHash, @Param("orgId") UUID orgId);

    @Modifying
    @Query("delete from JiraConnectStateEntity s where s.orgId = :orgId and s.expiresAt < :now")
    int deleteExpired(@Param("orgId") UUID orgId, @Param("now") Instant now);
}
