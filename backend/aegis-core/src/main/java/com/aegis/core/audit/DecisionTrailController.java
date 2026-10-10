package com.aegis.core.audit;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The decision audit trail, read-only: the chain of one item (its change in its service), one response or one
 * service, oldest first, then any record with the response as it stood then. Nothing here writes; records are made
 * by the decisions themselves.
 */
@RestController
@RequestMapping("/api/audit/decisions")
@SecurityRequirement(name = "bearer-jwt")
public class DecisionTrailController {

    /** Governance reads the trail: an analyst decides responses, a compliance admin audits them. */
    static final String READ = "hasAnyRole('ANALYST','COMPLIANCE_ADMIN')";

    private final DecisionTrail trail;

    public DecisionTrailController(DecisionTrail trail) {
        this.trail = trail;
    }

    @GetMapping
    @PreAuthorize(READ)
    @Operation(summary = "The decision audit trail, oldest first: every step a response took (drafted, edited, "
            + "overridden, approved, filed or opened, failed, dismissed), who took it and when, and why. Filter by "
            + "service (a regulator memo is in the trail of every service it covers), by change (with serviceId: one "
            + "item's chain) or by response. Each record's snapshot of the response is read on its own")
    public Page<DecisionRecord> list(
            @RequestParam(required = false) UUID serviceId,
            @RequestParam(required = false) UUID changeSignalId,
            @RequestParam(required = false) UUID responseId,
            @PageableDefault(size = 50, sort = "sequence", direction = Sort.Direction.ASC) Pageable pageable) {
        return trail.list(serviceId, changeSignalId, responseId, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    @Operation(summary = "One decision record, with the response as it stood then (state: the draft, any edit or "
            + "override, the evidence and score arithmetic, the decision and its result)")
    public DecisionRecord get(@PathVariable UUID id) {
        return trail.get(id);
    }
}
