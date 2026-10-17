package com.aegis.core.actions.memo;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.aegis.core.inventory.InventoryRoles;
import com.aegis.core.platform.common.ApiExceptions;
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
 * Regulator-notification memos: an analyst drafts a DORA notification for a change that materially affects
 * a service, and a second analyst approves it (final, ready for a person to submit) or dismisses it with a
 * reason. Aegis never sends a memo.
 */
@RestController
@RequestMapping("/api/actions/regulator-memos")
@SecurityRequirement(name = "bearer-jwt")
public class RegulatorMemoController {

    private final RegulatorMemoService memos;

    public RegulatorMemoController(RegulatorMemoService memos) {
        this.memos = memos;
    }

    public record ProposeMemoRequest(@NotNull UUID changeSignalId) {
    }

    public record DismissMemoRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    @PostMapping
    @PreAuthorize(MemoRoles.ANALYST)
    @Operation(summary = "Draft a DORA notification for a change that materially affects a service; nothing is sent")
    public ResponseEntity<RegulatorMemo> propose(@Valid @RequestBody ProposeMemoRequest request,
            Authentication authentication) {
        RegulatorMemo memo = memos.propose(request.changeSignalId(), callerId(authentication));
        return ResponseEntity.created(URI.create("/api/actions/regulator-memos/" + memo.id())).body(memo);
    }

    @GetMapping
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "List regulator memos, newest first, optionally for one change and in some statuses")
    public Page<RegulatorMemo> list(
            @RequestParam(required = false) UUID changeSignalId,
            @RequestParam(required = false) List<RegulatorMemoStatus> status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return memos.list(changeSignalId, status, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "One regulator memo, including the rendered notification")
    public RegulatorMemo get(@PathVariable UUID id) {
        return memos.get(id);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(MemoRoles.ANALYST)
    @Operation(summary = "Approve: the memo is final and ready to submit (an analyst other than the drafter)")
    public RegulatorMemo approve(@PathVariable UUID id, Authentication authentication) {
        return memos.approve(id, callerId(authentication));
    }

    @PostMapping("/{id}/dismiss")
    @PreAuthorize(MemoRoles.ANALYST)
    @Operation(summary = "Dismiss with a reason (an analyst, including the drafter)")
    public RegulatorMemo dismiss(@PathVariable UUID id, @Valid @RequestBody DismissMemoRequest request,
            Authentication authentication) {
        return memos.dismiss(id, callerId(authentication), request.reason());
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
