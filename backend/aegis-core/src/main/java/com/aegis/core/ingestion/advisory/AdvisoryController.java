package com.aegis.core.ingestion.advisory;

import com.aegis.core.common.ApiExceptions;
import com.aegis.core.ingestion.advisory.AdvisoryDtos.AdvisoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of what ingestion has normalised. Advisories are written by connectors, never by a
 * user, so there is no create/update/delete here — and the same Aegis domain read roles apply as to
 * the inventory API.
 */
@RestController
@RequestMapping("/api/advisories")
@SecurityRequirement(name = "bearer-jwt")
public class AdvisoryController {

    /** Same read roles as the inventory API; advisories are domain data, not platform administration. */
    private static final String READ = "hasAnyRole('SERVICE_OWNER','ANALYST','COMPLIANCE_ADMIN')";

    private final AdvisoryStore advisories;

    public AdvisoryController(AdvisoryStore advisories) {
        this.advisories = advisories;
    }

    @GetMapping
    @PreAuthorize(READ)
    @Operation(summary = "List normalised advisories, most recently revised first")
    public Page<AdvisoryResponse> listAdvisories(@PageableDefault(
            size = 20, sort = "lastModifiedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return advisories.findPage(pageable).map(AdvisoryResponse::from);
    }

    @GetMapping("/{sourceId}/{externalId}")
    @PreAuthorize(READ)
    @Operation(summary = "Get one advisory by feed and feed identifier, e.g. nvd / CVE-2021-44228")
    public AdvisoryResponse getAdvisory(@PathVariable String sourceId, @PathVariable String externalId) {
        return advisories.findByExternalId(sourceId, externalId)
                .map(AdvisoryResponse::from)
                .orElseThrow(() -> new ApiExceptions.NotFound(
                        "Advisory " + externalId + " not found for source " + sourceId));
    }
}
