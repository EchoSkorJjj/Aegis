package com.aegis.core.scoring;

import java.util.UUID;

import com.aegis.core.inventory.InventoryRoles;
import com.aegis.core.scoring.ScoringDtos.ChangeImpactResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only impact scoring: one change signal ranked across the services and owners it reaches. */
@RestController
@RequestMapping("/api/scoring/change-signals")
@SecurityRequirement(name = "bearer-jwt")
public class ScoringController {

    private final ScoringService scoring;

    public ScoringController(ScoringService scoring) {
        this.scoring = scoring;
    }

    @GetMapping("/{id}")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "Score one change signal per impacted service and owner, with its breakdown")
    public ChangeImpactResponse scoreChange(@PathVariable UUID id) {
        return scoring.scoreChange(id);
    }
}
