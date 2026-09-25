package com.aegis.core.inventory.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.NaturalId;

@Entity
@Table(name = "business_services")
public class BusinessService extends InventoryEntity {

    /**
     * The posture assumed for a service whose owner has not declared one. Matches the backfill in
     * migration V15 and the column default, so a row created before the attribute existed, a request
     * that omits it, and a direct construction all agree. Deliberately the middle level: assuming
     * {@code ISOLATED} would understate risk, assuming {@code INTERNET_FACING} would cry wolf.
     */
    public static final ServiceExposure DEFAULT_EXPOSURE = ServiceExposure.INTERNAL;

    @NaturalId(mutable = true)
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "criticality", nullable = false, length = 16)
    private BusinessCriticality criticality;

    @Enumerated(EnumType.STRING)
    @Column(name = "exposure", nullable = false, length = 32)
    private ServiceExposure exposure;

    @Column(name = "repository_url", length = 400)
    private String repositoryUrl;

    @Column(name = "repository_branch", length = 200)
    private String repositoryBranch;

    protected BusinessService() {
        // Required by JPA.
    }

    public BusinessService(UUID id, String name, BusinessCriticality criticality, ServiceExposure exposure) {
        super(id);
        this.name = requireText(name, "name");
        this.criticality = requireNonNull(criticality, "criticality");
        this.exposure = requireNonNull(exposure, "exposure");
    }

    /** Convenience for callers that do not state an exposure; applies {@link #DEFAULT_EXPOSURE}. */
    public BusinessService(UUID id, String name, BusinessCriticality criticality) {
        this(id, name, criticality, DEFAULT_EXPOSURE);
    }

    public void update(String name, BusinessCriticality criticality, ServiceExposure exposure) {
        this.name = requireText(name, "name");
        this.criticality = requireNonNull(criticality, "criticality");
        this.exposure = requireNonNull(exposure, "exposure");
    }

    /** Convenience for callers that do not state an exposure; leaves the current one unchanged. */
    public void update(String name, BusinessCriticality criticality) {
        update(name, criticality, this.exposure == null ? DEFAULT_EXPOSURE : this.exposure);
    }

    public String getName() {
        return name;
    }

    public void linkRepository(RepositoryUrl url, String branch) {
        String canonical = requireNonNull(url, "repositoryUrl").canonical();
        String normalizedBranch = RepositoryUrl.normalizeRef(branch);
        this.repositoryUrl = canonical;
        this.repositoryBranch = normalizedBranch;
    }

    public void unlinkRepository() {
        this.repositoryUrl = null;
        this.repositoryBranch = null;
    }

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    public String getRepositoryBranch() {
        return repositoryBranch;
    }

    public BusinessCriticality getCriticality() {
        return criticality;
    }

    public ServiceExposure getExposure() {
        return exposure;
    }
}
