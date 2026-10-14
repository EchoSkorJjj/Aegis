package com.aegis.core.inventory.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.NaturalId;
import org.hibernate.type.SqlTypes;

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

    @Column(name = "sbom_imported_at")
    private Instant sbomImportedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "sbom_import_source", length = 16)
    private SbomImportSource sbomImportSource;

    // The code scan of the last import from the repository (E11-3, V40); the modules it found are in service_imports.
    @Column(name = "import_scanned_at")
    private Instant importScannedAt;

    @Column(name = "import_scan_revision", length = 64)
    private String importScanRevision;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "import_scan_languages", nullable = false, columnDefinition = "text[]")
    private String[] importScanLanguages = new String[0];

    @Column(name = "import_scan_files")
    private Integer importScanFiles;

    @Column(name = "import_scan_limit", length = 300)
    private String importScanLimit;

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

    /**
     * Link (or re-link) the repository. Pointing at a different repository or branch forgets the last SBOM
     * import, which described the old one; saving the same link again keeps it.
     */
    public void linkRepository(RepositoryUrl url, String branch) {
        String canonical = requireNonNull(url, "repositoryUrl").canonical();
        String normalizedBranch = RepositoryUrl.normalizeRef(branch);
        if (!canonical.equals(repositoryUrl) || !Objects.equals(normalizedBranch, repositoryBranch)) {
            forgetSbomImport();
        }
        this.repositoryUrl = canonical;
        this.repositoryBranch = normalizedBranch;
    }

    public void unlinkRepository() {
        this.repositoryUrl = null;
        this.repositoryBranch = null;
        forgetSbomImport();
    }

    /** A successful SBOM import at {@code at}, and how it got in. */
    public void recordSbomImport(SbomImportSource source, Instant at) {
        this.sbomImportSource = requireNonNull(source, "source");
        this.sbomImportedAt = requireNonNull(at, "at");
    }

    /**
     * The code scan of an import from the repository at {@code at}: the commit it read, the languages it read files of,
     * how many, and why it stopped early if it did. The modules it found are stored beside the service.
     */
    public void recordImportScan(ImportScan scan, Instant at) {
        requireNonNull(scan, "scan");
        this.importScannedAt = requireNonNull(at, "at");
        this.importScanRevision = scan.revision();
        this.importScanLanguages = scan.languages().toArray(String[]::new);
        this.importScanFiles = scan.filesRead();
        this.importScanLimit = scan.limit() == null || scan.limit().length() <= 300 ? scan.limit()
                : scan.limit().substring(0, 299) + "…";
    }

    private void forgetSbomImport() {
        this.sbomImportSource = null;
        this.sbomImportedAt = null;
        // The scan read the old repository's code.
        this.importScannedAt = null;
        this.importScanRevision = null;
        this.importScanLanguages = new String[0];
        this.importScanFiles = null;
        this.importScanLimit = null;
    }

    /** When the code was last scanned at an import from the repository; null when it never was (or the link moved). */
    public Instant getImportScannedAt() {
        return importScannedAt;
    }

    public String getImportScanRevision() {
        return importScanRevision;
    }

    public List<String> getImportScanLanguages() {
        return importScanLanguages == null ? List.of() : List.of(importScanLanguages);
    }

    public Integer getImportScanFiles() {
        return importScanFiles;
    }

    /** Why the last scan stopped before reading everything; null when it read it all. */
    public String getImportScanLimit() {
        return importScanLimit;
    }

    public Instant getSbomImportedAt() {
        return sbomImportedAt;
    }

    public SbomImportSource getSbomImportSource() {
        return sbomImportSource;
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
