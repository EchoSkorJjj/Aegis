package com.aegis.core.findings;

import java.util.UUID;

import com.aegis.core.findings.FindingDtos.Queue;
import com.aegis.core.inventory.InventoryRoles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/findings")
@SecurityRequirement(name = "bearer-jwt")
public class FindingController {
    private final FindingService findings;

    public FindingController(FindingService findings) { this.findings = findings; }

    @GetMapping
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "Material changes ranked by impact, filtered by service or assigned owner")
    public Queue list(@RequestParam(required = false) UUID serviceId,
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(defaultValue = "false") boolean mine,
            @AuthenticationPrincipal Jwt jwt,
            @PageableDefault(size = 20) Pageable pageable) {
        return findings.list(serviceId, ownerId, UUID.fromString(jwt.getSubject()), mine, pageable);
    }
}
