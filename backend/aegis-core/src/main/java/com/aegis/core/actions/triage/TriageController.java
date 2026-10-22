package com.aegis.core.actions.triage;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.aegis.core.actions.triage.TriageQueue.Caller;
import com.aegis.core.actions.triage.TriageQueue.Filters;
import com.aegis.core.actions.triage.TriageQueue.OwnedService;
import com.aegis.core.actions.triage.TriageQueue.Queue;
import com.aegis.core.inventory.InventoryRoles;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The triage queue the frontend's /findings reads, and the service owner's view of it /findings/mine reads. */
@RestController
@RequestMapping("/api/findings")
@SecurityRequirement(name = "bearer-jwt")
public class TriageController {

    private final TriageQueue queue;

    public TriageController(TriageQueue queue) {
        this.queue = queue;
    }

    @GetMapping
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "What to respond to next: material vulnerabilities and active vendor outages, highest impact first")
    public Queue list(
            @RequestParam(required = false) UUID serviceId,
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "false", name = "open") boolean openOnly,
            @RequestParam(defaultValue = "false") boolean unassigned,
            @RequestParam(required = false) String ecosystem,
            @RequestParam(required = false) ImpactTier tier,
            @PageableDefault(size = 20) Pageable pageable,
            Authentication authentication) {
        String ecosystemFilter = ecosystem == null || ecosystem.isBlank() ? null : ecosystem.strip();
        return queue.list(new Filters(serviceId, ownerId, mine, ecosystemFilter, tier), caller(authentication),
                pageable, openOnly, unassigned);
    }

    @GetMapping("/my-services")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "The services you are an assigned owner of: what each needs a response to, what waits for "
            + "a decision, and where Aegis's view of its dependencies comes from")
    public List<OwnedService> myServices(Authentication authentication) {
        return queue.ownedBy(caller(authentication));
    }

    private static Caller caller(Authentication authentication) {
        Set<String> roles = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        return new Caller(UUID.fromString(authentication.getName()), roles.contains("ROLE_ANALYST"),
                roles.contains("ROLE_SERVICE_OWNER"));
    }
}
