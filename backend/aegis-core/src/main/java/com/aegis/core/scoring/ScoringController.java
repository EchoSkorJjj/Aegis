package com.aegis.core.scoring;

import java.time.Instant;
import java.util.UUID;

import com.aegis.core.inventory.InventoryRoles;
import com.aegis.core.scoring.ScoringDtos.ChangeImpactResponse;
import com.aegis.core.scoring.ScoringDtos.EstateRollupResponse;
import com.aegis.core.signals.ChangeSignal.SignalType;
import com.aegis.core.signals.ChangeSignalFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only impact scoring: one change signal, or the whole estate rolled up over a window. */
@RestController
@RequestMapping("/api/scoring")
@SecurityRequirement(name = "bearer-jwt")
public class ScoringController {

    private final ScoringService scoring;

    public ScoringController(ScoringService scoring) {
        this.scoring = scoring;
    }

    @GetMapping("/change-signals/{id}")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "Score one change signal per impacted service and owner, with each score's contributing factors")
    public ChangeImpactResponse scoreChange(@PathVariable UUID id) {
        return scoring.scoreChange(id);
    }

    @GetMapping("/rollups")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "Roll every scored signal in a window up per service and per owner, explaining each top score")
    public EstateRollupResponse rollUp(
            @RequestParam(required = false) SignalType type,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String ecosystem,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer limit) {
        ChangeSignalFilter filter =
                new ChangeSignalFilter(type, severity, source, search, ecosystem, from, to);
        return scoring.rollUpEstate(filter, limit);
    }
}
