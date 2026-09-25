package com.aegis.core.actions.upgrade;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.aegis.core.inventory.InventoryRoles;
import com.aegis.core.platform.common.ApiExceptions;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Safe-upgrade proposals: draft one, then a person approves (Aegis opens a draft pull request) or
 * dismisses it with a reason. Every response is the proposal as stored, plus its service's current name.
 */
@RestController
@RequestMapping("/api/actions/upgrade-proposals")
@SecurityRequirement(name = "bearer-jwt")
public class UpgradeProposalController {

    private final UpgradeProposalService proposals;

    public UpgradeProposalController(UpgradeProposalService proposals) {
        this.proposals = proposals;
    }

    /** Which vulnerable component of which service to upgrade; the manifest path defaults per ecosystem. */
    public record ProposeUpgradeRequest(
            @NotNull UUID changeSignalId,
            @NotNull UUID serviceId,
            @NotNull UUID componentId,
            @Size(max = 400) String manifestPath) {
    }

    public record DismissUpgradeRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    /** The proposal's own fields, flat, and the name of the service it belongs to. */
    public record UpgradeProposalResponse(@JsonUnwrapped UpgradeProposal proposal, String serviceName) {
    }

    @PostMapping
    @PreAuthorize(UpgradeRoles.PROPOSE)
    @Operation(summary = "Draft a safe-upgrade pull request for a vulnerable component; nothing is opened until approved")
    public ResponseEntity<UpgradeProposalResponse> propose(
            @Valid @RequestBody ProposeUpgradeRequest request, Authentication authentication) {
        UpgradeProposal proposal = proposals.propose(request.changeSignalId(), request.serviceId(),
                request.componentId(), request.manifestPath(), callerId(authentication));
        return ResponseEntity.created(URI.create("/api/actions/upgrade-proposals/" + proposal.id()))
                .body(respond(proposal));
    }

    @GetMapping
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "List upgrade proposals, newest first, optionally for one service or change and in some statuses")
    public Page<UpgradeProposalResponse> list(
            @RequestParam(required = false) UUID serviceId,
            @RequestParam(required = false) UUID changeSignalId,
            @RequestParam(required = false) List<UpgradeProposalStatus> status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<UpgradeProposal> page = proposals.list(serviceId, changeSignalId, status, pageable);
        Set<UUID> serviceIds = page.getContent().stream().map(UpgradeProposal::serviceId).collect(Collectors.toSet());
        Map<UUID, String> names = proposals.serviceNames(serviceIds);
        return page.map(proposal -> new UpgradeProposalResponse(proposal, names.get(proposal.serviceId())));
    }

    @GetMapping("/{id}")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "One upgrade proposal, including the exact pull-request title and body")
    public UpgradeProposalResponse get(@PathVariable UUID id) {
        return respond(proposals.get(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(UpgradeRoles.DECIDE)
    @Operation(summary = "Approve: open the draft pull request (an analyst or the service's owners, never the drafter; Aegis never merges)")
    public UpgradeProposalResponse approve(@PathVariable UUID id, Authentication authentication) {
        return respond(proposals.approve(id, callerId(authentication)));
    }

    @PostMapping("/{id}/dismiss")
    @PreAuthorize(UpgradeRoles.DECIDE)
    @Operation(summary = "Dismiss with a reason (an analyst or the service's owners, including the drafter)")
    public UpgradeProposalResponse dismiss(
            @PathVariable UUID id, @Valid @RequestBody DismissUpgradeRequest request, Authentication authentication) {
        return respond(proposals.dismiss(id, callerId(authentication), request.reason()));
    }

    private UpgradeProposalResponse respond(UpgradeProposal proposal) {
        return new UpgradeProposalResponse(proposal, proposals.serviceNames(Set.of(proposal.serviceId())).get(proposal.serviceId()));
    }

    /** The caller's identity-service user id: the access token's subject. */
    private static UUID callerId(Authentication authentication) {
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException | NullPointerException notAUser) {
            throw new ApiExceptions.Forbidden("Only a signed-in user can make this decision");
        }
    }
}
