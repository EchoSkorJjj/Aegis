package com.aegis.core.audit;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.aegis.core.audit.DecisionRecord.Action;
import com.aegis.core.audit.DecisionRecord.Subject;
import com.aegis.core.platform.common.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The decision audit trail, read-only: searched by service, change, response, the person who took each step, the
 * step and a period (E10 story 2), oldest or newest first, then any record with the response as it stood then.
 * Nothing here writes; records are made by the decisions themselves.
 */
@RestController
@RequestMapping("/api/audit/decisions")
@SecurityRequirement(name = "bearer-jwt")
public class DecisionTrailController {

    /** Governance reads the trail: an analyst decides responses, a compliance admin audits them. */
    static final String READ = "hasAnyRole('ANALYST','COMPLIANCE_ADMIN')";

    /** The one order the trail has: the order it was written in ({@code occurredAt} with ties broken). */
    private static final String SEQUENCE = "sequence";

    private final DecisionTrail trail;

    public DecisionTrailController(DecisionTrail trail) {
        this.trail = trail;
    }

    @GetMapping
    @PreAuthorize(READ)
    @Operation(summary = "Search the decision audit trail: every step a response took (drafted, edited, overridden, "
            + "approved, filed or opened, failed, dismissed), who took it and when, and why. Filter by service (a "
            + "regulator memo is in the trail of every service it covers), change (with serviceId: one item's chain), "
            + "response, the person who took the step, the step, what it was taken on, and a period [from, to). "
            + "Oldest first unless sort=sequence,desc. Each record's snapshot of the response is read on its own")
    public Page<DecisionRecord> list(
            @RequestParam(required = false) UUID serviceId,
            @RequestParam(required = false) UUID changeSignalId,
            @RequestParam(required = false) UUID responseId,
            @Parameter(description = "The person who took the step (their user id)")
            @RequestParam(required = false) UUID actorId,
            @Parameter(description = "The steps to include, comma-separated (any of them)")
            @RequestParam(required = false) List<Action> action,
            @Parameter(description = "What the step was taken on, comma-separated (any of them)")
            @RequestParam(required = false) List<Subject> subject,
            @Parameter(description = "Taken at or after this instant (ISO-8601)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Taken before this instant (ISO-8601, exclusive)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 50, sort = SEQUENCE, direction = Sort.Direction.ASC) Pageable pageable) {
        DecisionTrailQuery query = new DecisionTrailQuery(serviceId, changeSignalId, responseId, actorId,
                action == null ? null : Set.copyOf(action),
                subject == null ? null : Set.copyOf(subject), from, to);
        return trail.list(query, inWrittenOrder(pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    @Operation(summary = "One decision record, with the response as it stood then (state: the draft, any edit or "
            + "override, the evidence and score arithmetic, the decision and its result)")
    public DecisionRecord get(@PathVariable UUID id) {
        return trail.get(id);
    }

    /**
     * The trail is read in the order it was written, either way: any other sort (by the copied service name, by the
     * snapshot) would answer no audit question and could only be slow, so it's refused rather than ignored.
     */
    private static Pageable inWrittenOrder(Pageable pageable) {
        Sort.Direction direction = Sort.Direction.ASC;
        for (Sort.Order order : pageable.getSort()) {
            if (!SEQUENCE.equals(order.getProperty()) && !"occurredAt".equals(order.getProperty())) {
                throw new ApiExceptions.BadRequest("The trail sorts only by sequence (or occurredAt), oldest or "
                        + "newest first");
            }
            direction = order.getDirection();
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(direction, SEQUENCE));
    }
}
