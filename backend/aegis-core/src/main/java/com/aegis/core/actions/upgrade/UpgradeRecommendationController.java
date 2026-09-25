package com.aegis.core.actions.upgrade;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aegis.core.actions.upgrade.UpgradeRecommendations.Recommendation;
import com.aegis.core.inventory.InventoryRoles;
import com.aegis.core.inventory.ServiceOwnership;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The upgrades Aegis recommends for the caller's organization — or one service — highest impact first,
 * with no CVE to look up. Reading them takes any domain role; drafting one is the upgrade proposals
 * endpoint, under its own rules ({@link UpgradeRoles#PROPOSE}). {@code canDraft} says whether this caller may
 * draft that one — an analyst, or the service's assigned owner — so the page offers the button only where
 * it would work.
 */
@RestController
@RequestMapping("/api/actions/upgrade-recommendations")
@SecurityRequirement(name = "bearer-jwt")
public class UpgradeRecommendationController {

    private final UpgradeRecommendations recommendations;
    private final ServiceOwnership ownership;

    public UpgradeRecommendationController(UpgradeRecommendations recommendations, ServiceOwnership ownership) {
        this.recommendations = recommendations;
        this.ownership = ownership;
    }

    public record RecommendationResponse(@JsonUnwrapped Recommendation recommendation, boolean canDraft) {
    }

    @GetMapping
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "Upgrades recommended for the organization's vulnerable dependencies, highest impact first")
    public List<RecommendationResponse> list(
            @RequestParam(required = false) UUID serviceId, Authentication authentication) {
        boolean anyService = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).anyMatch(UpgradeRoles.ANY_SERVICE::equals);
        Map<UUID, Boolean> owns = new HashMap<>();
        return recommendations.recommend(serviceId).stream()
                .map(recommendation -> new RecommendationResponse(recommendation, anyService
                        || owns.computeIfAbsent(recommendation.serviceId(),
                                id -> ownership.isAssignedOwner(id, authentication))))
                .toList();
    }
}
