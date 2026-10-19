package com.aegis.core.matching;

import java.util.UUID;

import com.aegis.core.matching.MatchingDtos.ChangeMatchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only contextualization API: signal -> affected estate -> evidence explaining why. */
@RestController
@RequestMapping("/api/matching/change-signals")
@SecurityRequirement(name = "bearer-jwt")
public class MatchingController {

    private static final String READ = "hasAnyRole('SERVICE_OWNER','ANALYST','COMPLIANCE_ADMIN')";

    private final MatchingService matching;

    public MatchingController(MatchingService matching) {
        this.matching = matching;
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    @Operation(summary = "Match one change signal to affected services and return its evidence chains")
    public ChangeMatchResponse matchChange(@PathVariable UUID id) {
        return matching.matchChange(id);
    }
}
