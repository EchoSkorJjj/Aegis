package com.aegis.core.actions.dashboard;

import com.aegis.core.actions.dashboard.RiskPosture.Dashboard;
import com.aegis.core.inventory.InventoryRoles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/findings/posture")
@SecurityRequirement(name = "bearer-jwt")
public class RiskPostureController {
    private final RiskPosture posture;

    public RiskPostureController(RiskPosture posture) {
        this.posture = posture;
    }

    @GetMapping
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "Open material items by service and owner, with daily observed totals")
    public Dashboard read(@RequestParam(defaultValue = "30") int days) {
        return posture.read(days);
    }
}
