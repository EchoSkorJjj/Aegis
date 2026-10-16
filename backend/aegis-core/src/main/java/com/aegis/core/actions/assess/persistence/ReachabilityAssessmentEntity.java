package com.aegis.core.actions.assess.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.aegis.core.actions.assess.ReachabilityAssessment;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** One AI reachability suggestion (V41, V43): the answer, its checks, what was searched, the line ranges it cites. */
@Entity
@Table(name = "reachability_assessments")
class ReachabilityAssessmentEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "business_service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Column(name = "ecosystem", nullable = false, updatable = false, length = 32)
    private String ecosystem;

    @Column(name = "package_name", nullable = false, updatable = false)
    private String packageName;

    @Column(name = "vulnerability_id", nullable = false, updatable = false, length = 128)
    private String vulnerabilityId;

    @Column(name = "scan_revision", nullable = false, updatable = false, length = 64)
    private String revision;

    @Column(name = "verdict", nullable = false, length = 16)
    private String verdict;

    @Column(name = "rationale", nullable = false, length = 4000)
    private String rationale;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "cited_lines", nullable = false, columnDefinition = "text[]")
    private String[] citedLines = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "checks", nullable = false, columnDefinition = "text[]")
    private String[] checks = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "searches", nullable = false, columnDefinition = "text[]")
    private String[] searches = new String[0];

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(name = "assessed_at", nullable = false)
    private Instant assessedAt;

    protected ReachabilityAssessmentEntity() {
    }

    ReachabilityAssessment toDomain() {
        return new ReachabilityAssessment(id, serviceId, ecosystem, packageName, vulnerabilityId, revision,
                ReachabilityAssessment.Verdict.valueOf(verdict), rationale,
                List.of(citedLines).stream().map(ReachabilityAssessment.Cited::decode).toList(), List.of(checks),
                List.of(searches).stream().map(ReachabilityAssessment.Searched::decode).toList(), requestedBy,
                assessedAt);
    }
}

interface ReachabilityAssessmentJpaRepository extends JpaRepository<ReachabilityAssessmentEntity, UUID> {

    @Query("select a from ReachabilityAssessmentEntity a where a.orgId = :org and a.serviceId in :services"
            + " and a.vulnerabilityId = :vulnerability")
    List<ReachabilityAssessmentEntity> findFor(@Param("org") UUID org, @Param("services") Collection<UUID> services,
            @Param("vulnerability") String vulnerabilityId);

    /** Insert, or replace the answer for the same service, package, vulnerability and commit. */
    @Modifying
    @Query(value = """
            insert into {h-schema}reachability_assessments (id, org_id, business_service_id, ecosystem, package_name,
                vulnerability_id, scan_revision, verdict, rationale, cited_lines, checks, searches, requested_by,
                assessed_at)
            values (:id, :org, :service, :ecosystem, :packageName, :vulnerability, :revision, :verdict, :rationale,
                cast(:citedLines as text[]), cast(:checks as text[]), cast(:searches as text[]), :requestedBy,
                :assessedAt)
            on conflict (business_service_id, ecosystem, package_name, vulnerability_id, scan_revision) do update
                set id = excluded.id, verdict = excluded.verdict, rationale = excluded.rationale,
                    cited_lines = excluded.cited_lines, checks = excluded.checks, searches = excluded.searches,
                    requested_by = excluded.requested_by, assessed_at = excluded.assessed_at
            """, nativeQuery = true)
    int upsert(@Param("id") UUID id, @Param("org") UUID org, @Param("service") UUID serviceId,
            @Param("ecosystem") String ecosystem, @Param("packageName") String packageName,
            @Param("vulnerability") String vulnerabilityId, @Param("revision") String revision,
            @Param("verdict") String verdict, @Param("rationale") String rationale,
            @Param("citedLines") String[] citedLines, @Param("checks") String[] checks,
            @Param("searches") String[] searches, @Param("requestedBy") UUID requestedBy,
            @Param("assessedAt") Instant assessedAt);
}
