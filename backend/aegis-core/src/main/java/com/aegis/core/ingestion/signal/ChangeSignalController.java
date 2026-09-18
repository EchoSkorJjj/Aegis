package com.aegis.core.ingestion.signal;

import java.time.Instant;
import java.util.UUID;

import com.aegis.core.common.ApiExceptions;
import com.aegis.core.ingestion.signal.ChangeSignal.SignalType;
import com.aegis.core.ingestion.signal.ChangeSignalDtos.ChangeSignalFacets;
import com.aegis.core.ingestion.signal.ChangeSignalDtos.ChangeSignalResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only, unified view across every normalised ingestion output (advisories, vendor incidents,
 * security news). Like the per-source APIs it fronts, signals are written only by connectors, never
 * by a user — there is no create/update/delete here, and the same Aegis domain read roles apply.
 */
@RestController
@RequestMapping("/api/change-signals")
@SecurityRequirement(name = "bearer-jwt")
public class ChangeSignalController {

    /** Same read roles as the rest of the ingestion API; signals are domain data, not administration. */
    private static final String READ = "hasAnyRole('SERVICE_OWNER','ANALYST','COMPLIANCE_ADMIN')";

    private final ChangeSignalStore signals;

    public ChangeSignalController(ChangeSignalStore signals) {
        this.signals = signals;
    }

    @GetMapping
    @PreAuthorize(READ)
    @Operation(summary = "List change signals, optionally filtered by type, severity, source, text, and date")
    public Page<ChangeSignalResponse> listSignals(
            @RequestParam(required = false) SignalType type,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20, sort = "occurredAt", direction = Sort.Direction.DESC) Pageable pageable) {
        ChangeSignalFilter filter = new ChangeSignalFilter(type, severity, source, search, from, to);
        return signals.findPage(filter, pageable).map(ChangeSignalResponse::from);
    }

    @GetMapping("/facets")
    @PreAuthorize(READ)
    @Operation(summary = "Distinct sources and severities present — powers the advisory filter selects")
    public ChangeSignalFacets facets() {
        return new ChangeSignalFacets(signals.findSources(), signals.findSeverities());
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    @Operation(summary = "Get one change signal by its id")
    public ChangeSignalResponse getSignal(@PathVariable UUID id) {
        return signals.findById(id)
                .map(ChangeSignalResponse::from)
                .orElseThrow(() -> new ApiExceptions.NotFound("Change signal not found: " + id));
    }
}
