package com.aegis.core.incidents;

import com.aegis.core.incidents.VendorIncidentDtos.VendorIncidentResponse;
import com.aegis.core.platform.common.ApiExceptions;
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
 * Read-only view of the vendor outages ingestion has normalised. Written by connectors, never by a
 * user, and gated by the same Aegis domain read roles as the inventory and advisory APIs.
 */
@RestController
@RequestMapping("/api/vendor-incidents")
@SecurityRequirement(name = "bearer-jwt")
public class VendorIncidentController {

    private static final String READ = "hasAnyRole('SERVICE_OWNER','ANALYST','COMPLIANCE_ADMIN')";

    private final VendorIncidentStore incidents;

    public VendorIncidentController(VendorIncidentStore incidents) {
        this.incidents = incidents;
    }

    @GetMapping
    @PreAuthorize(READ)
    @Operation(summary = "List normalised vendor incidents, most recently started first")
    public Page<VendorIncidentResponse> listIncidents(@PageableDefault(
            size = 20, sort = "startedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return incidents.findPage(pageable).map(VendorIncidentResponse::from);
    }

    @GetMapping("/{sourceId}/{externalId}")
    @PreAuthorize(READ)
    @Operation(summary = "Get one incident by feed and feed identifier, e.g. vendor-status / github:abc123")
    public VendorIncidentResponse getIncident(@PathVariable String sourceId, @PathVariable String externalId) {
        return incidents.findByExternalId(sourceId, externalId)
                .map(VendorIncidentResponse::from)
                .orElseThrow(() -> new ApiExceptions.NotFound(
                        "Incident " + externalId + " not found for source " + sourceId));
    }
}
