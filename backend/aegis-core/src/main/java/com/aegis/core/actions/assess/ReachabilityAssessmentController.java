package com.aegis.core.actions.assess;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import com.aegis.core.inventory.InventoryRoles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The AI's suggestion on whether each service's code calls the vulnerable function (E11-5). */
@RestController
@RequestMapping("/api/actions/reachability-assessments")
@SecurityRequirement(name = "bearer-jwt")
public class ReachabilityAssessmentController {

    private final ReachabilityAssessments assessments;

    public ReachabilityAssessmentController(ReachabilityAssessments assessments) {
        this.assessments = assessments;
    }

    public record AssessRequest(@NotNull UUID serviceId, @NotNull UUID changeSignalId) {
    }

    @GetMapping
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "For each service a change reaches, the AI's suggestion on whether its code calls the "
            + "vulnerable function, with the lines it cites — or why there is none")
    public List<ReachabilityAssessments.Item> list(@RequestParam UUID changeSignalId) {
        return assessments.list(changeSignalId);
    }

    @PostMapping
    @PreAuthorize(AssessRoles.ASK)
    @Operation(summary = "Ask the AI now whether the service's code calls the vulnerable function: a suggestion, which "
            + "never changes the score")
    public List<ReachabilityAssessments.Item> assess(@Valid @RequestBody AssessRequest request,
            Authentication authentication) {
        return assessments.assess(request.serviceId(), request.changeSignalId(),
                UUID.fromString(authentication.getName()));
    }
}
