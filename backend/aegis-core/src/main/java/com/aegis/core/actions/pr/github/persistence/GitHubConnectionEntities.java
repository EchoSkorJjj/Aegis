package com.aegis.core.actions.pr.github.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.aegis.core.actions.pr.github.GitHubConnectionStore.Connection;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** An organization's connected GitHub App installation (V22). */
@Entity
@Table(name = "github_connections")
class GitHubConnectionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "installation_id", nullable = false, updatable = false)
    private long installationId;

    @Column(name = "account_login", nullable = false, length = 100)
    private String accountLogin;

    @Column(name = "account_id", nullable = false)
    private long accountId;

    @Column(name = "account_type", nullable = false, length = 20)
    private String accountType;

    @Column(name = "connected_by", nullable = false, updatable = false)
    private UUID connectedBy;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "connected_at", nullable = false, updatable = false)
    private Instant connectedAt;

    protected GitHubConnectionEntity() {
    }

    GitHubConnectionEntity(UUID orgId, long installationId, UUID connectedBy) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.installationId = installationId;
        this.connectedBy = connectedBy;
    }

    /** The account can be renamed on GitHub; the installation and who connected it stay. */
    void account(String login, long id, String type) {
        this.accountLogin = login;
        this.accountId = id;
        this.accountType = type;
    }

    Connection toDomain() {
        return new Connection(installationId, accountLogin, accountId, accountType, connectedBy, connectedAt);
    }
}

/** A connection in progress, under the SHA-256 of the state it handed GitHub (V22). */
@Entity
@Table(name = "github_connect_states")
class GitHubConnectStateEntity {

    @Id
    @Column(name = "state_hash", nullable = false, updatable = false, length = 64)
    private String stateHash;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    protected GitHubConnectStateEntity() {
    }

    GitHubConnectStateEntity(String stateHash, UUID orgId, UUID userId, Instant expiresAt) {
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

interface GitHubConnectionJpaRepository extends JpaRepository<GitHubConnectionEntity, UUID> {

    List<GitHubConnectionEntity> findByOrgIdOrderByAccountLoginAsc(UUID orgId);

    Optional<GitHubConnectionEntity> findByOrgIdAndInstallationId(UUID orgId, long installationId);

    boolean existsByOrgIdAndInstallationId(UUID orgId, long installationId);

    boolean existsByOrgId(UUID orgId);

    @Modifying
    @Query("delete from GitHubConnectionEntity c where c.orgId = :orgId and c.installationId = :installationId")
    int deleteConnection(@Param("orgId") UUID orgId, @Param("installationId") long installationId);
}

interface GitHubConnectStateJpaRepository extends JpaRepository<GitHubConnectStateEntity, String> {

    Optional<GitHubConnectStateEntity> findByStateHashAndOrgId(String stateHash, UUID orgId);

    /** A bulk delete, so two completions racing on one state cannot both see it removed. */
    @Modifying
    @Query("delete from GitHubConnectStateEntity s where s.stateHash = :stateHash and s.orgId = :orgId")
    int deleteState(@Param("stateHash") String stateHash, @Param("orgId") UUID orgId);

    @Modifying
    @Query("delete from GitHubConnectStateEntity s where s.orgId = :orgId and s.expiresAt < :now")
    int deleteExpired(@Param("orgId") UUID orgId, @Param("now") Instant now);
}
