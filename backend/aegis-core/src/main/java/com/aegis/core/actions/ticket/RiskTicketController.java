package com.aegis.core.actions.ticket;

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

import com.aegis.core.actions.upgrade.UpgradeProposal;
import com.aegis.core.inventory.InventoryRoles;
import com.aegis.core.platform.common.ApiExceptions;
import com.fasterxml.jackson.annotation.JsonInclude;
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
 * Responses: the risk ticket for a change's impact on a service and, for a vulnerability, the upgrade pull
 * request that fixes it. Draft one, then approve it (Aegis files the ticket in the organization's issue
 * tracker and opens the pull request as a draft with the ticket's key) or dismiss it with a reason. Every
 * response carries its pull requests; one also comes with its ServiceNow shape, for organizations that track
 * risks there.
 */
@RestController
@RequestMapping("/api/actions/risk-tickets")
@SecurityRequirement(name = "bearer-jwt")
public class RiskTicketController {

    private final RiskTicketService tickets;

    public RiskTicketController(RiskTicketService tickets) {
        this.tickets = tickets;
    }

    /** Which change's impact on which service to draft a response for. */
    public record ProposeTicketRequest(@NotNull UUID changeSignalId, @NotNull UUID serviceId) {
    }

    public record DismissTicketRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    /**
     * The ticket's own fields, flat, its service's current name, its pull requests (newest first; at most one
     * not dismissed) and — for one ticket — its ServiceNow shape.
     */
    public record RiskTicketResponse(
            @JsonUnwrapped RiskTicket ticket,
            String serviceName,
            List<UpgradeProposal> pullRequests,
            @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> serviceNow) {
    }

    @PostMapping
    @PreAuthorize(TicketRoles.PROPOSE)
    @Operation(summary = "Draft a response: the risk ticket for a change's impact on a service, and the upgrade pull "
            + "request that fixes it where Aegis can automate one; nothing leaves Aegis until approved")
    public ResponseEntity<RiskTicketResponse> propose(
            @Valid @RequestBody ProposeTicketRequest request, Authentication authentication) {
        RiskTicket ticket = tickets.propose(request.changeSignalId(), request.serviceId(), callerId(authentication));
        return ResponseEntity.created(URI.create("/api/actions/risk-tickets/" + ticket.id())).body(respond(ticket));
    }

    @GetMapping
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "List responses, newest first: for one service or change, in some statuses, or those that "
            + "still need a person (pending=true: the ticket or its pull request awaits a decision, or failed)")
    public Page<RiskTicketResponse> list(
            @RequestParam(required = false) UUID serviceId,
            @RequestParam(required = false) UUID changeSignalId,
            @RequestParam(required = false) List<RiskTicketStatus> status,
            @RequestParam(required = false) Boolean pending,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<RiskTicket> page = tickets.list(serviceId, changeSignalId, status, pending, pageable);
        Set<UUID> serviceIds = page.getContent().stream().map(RiskTicket::serviceId).collect(Collectors.toSet());
        Map<UUID, String> names = tickets.serviceNames(serviceIds);
        Map<UUID, List<UpgradeProposal>> pullRequests =
                tickets.pullRequests(page.getContent().stream().map(RiskTicket::id).toList());
        return page.map(ticket -> new RiskTicketResponse(ticket, names.get(ticket.serviceId()),
                pullRequests.getOrDefault(ticket.id(), List.of()), null));
    }

    @GetMapping("/{id}")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "One response, with its pull requests and its ServiceNow incident shape")
    public RiskTicketResponse get(@PathVariable UUID id) {
        return respond(tickets.get(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(TicketRoles.DECIDE)
    @Operation(summary = "Approve: file the ticket in the organization's tracker, then open its pull request with the "
            + "ticket's key (an analyst or the service's owners; never the drafter when there is a pull request)")
    public RiskTicketResponse approve(@PathVariable UUID id, Authentication authentication) {
        return respond(tickets.approve(id, callerId(authentication)));
    }

    @PostMapping("/{id}/dismiss")
    @PreAuthorize(TicketRoles.DECIDE)
    @Operation(summary = "Dismiss the response with a reason, its pull request too (an analyst or the service's "
            + "owners, including the drafter)")
    public RiskTicketResponse dismiss(
            @PathVariable UUID id, @Valid @RequestBody DismissTicketRequest request, Authentication authentication) {
        return respond(tickets.dismiss(id, callerId(authentication), request.reason()));
    }

    @PostMapping("/{id}/pull-requests")
    @PreAuthorize(TicketRoles.DECIDE)
    @Operation(summary = "Draft the upgrade pull request of a response that has none (an analyst or the service's owners)")
    public ResponseEntity<RiskTicketResponse> draftPullRequest(@PathVariable UUID id, Authentication authentication) {
        tickets.draftPullRequest(id, callerId(authentication));
        return ResponseEntity.created(URI.create("/api/actions/risk-tickets/" + id)).body(respond(tickets.get(id)));
    }

    @PostMapping("/{id}/pull-requests/{proposalId}/approve")
    @PreAuthorize(TicketRoles.DECIDE)
    @Operation(summary = "Open a filed response's pull request on its own, with the ticket's key: left for later, "
            + "drafted afterwards, or failed (an analyst or the service's owners, never its drafter)")
    public RiskTicketResponse approvePullRequest(
            @PathVariable UUID id, @PathVariable UUID proposalId, Authentication authentication) {
        tickets.approvePullRequest(id, proposalId, callerId(authentication));
        return respond(tickets.get(id));
    }

    private RiskTicketResponse respond(RiskTicket ticket) {
        String name = tickets.serviceNames(Set.of(ticket.serviceId())).get(ticket.serviceId());
        return new RiskTicketResponse(ticket, name,
                tickets.pullRequests(List.of(ticket.id())).getOrDefault(ticket.id(), List.of()),
                tickets.serviceNow(ticket));
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
