package com.aegis.core.advisories;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AdvisoryDtos {

    private AdvisoryDtos() {
    }

    public record AdvisoryResponse(
            UUID id,
            String sourceId,
            String externalId,
            String description,
            Instant publishedAt,
            Instant lastModifiedAt,
            String cvssVersion,
            String cvssVector,
            BigDecimal cvssBaseScore,
            String cvssSeverity,
            List<AffectedProductResponse> affected) {

        static AdvisoryResponse from(Advisory advisory) {
            Advisory.Cvss cvss = advisory.cvss();
            return new AdvisoryResponse(
                    advisory.id(),
                    advisory.sourceId(),
                    advisory.externalId(),
                    advisory.description(),
                    advisory.publishedAt(),
                    advisory.lastModifiedAt(),
                    cvss == null ? null : cvss.version(),
                    cvss == null ? null : cvss.vectorString(),
                    cvss == null ? null : cvss.baseScore(),
                    cvss == null ? null : cvss.severity(),
                    advisory.affected().stream().map(AffectedProductResponse::from).toList());
        }
    }

    public record AffectedProductResponse(
            String cpe,
            String part,
            String vendor,
            String product,
            String version,
            String versionStartIncluding,
            String versionStartExcluding,
            String versionEndIncluding,
            String versionEndExcluding,
            boolean allVersions) {

        static AffectedProductResponse from(AffectedProduct affected) {
            return new AffectedProductResponse(
                    affected.cpe(),
                    affected.part(),
                    affected.vendor(),
                    affected.product(),
                    affected.version(),
                    affected.versionStartIncluding(),
                    affected.versionStartExcluding(),
                    affected.versionEndIncluding(),
                    affected.versionEndExcluding(),
                    affected.affectsAllVersions());
        }
    }
}
