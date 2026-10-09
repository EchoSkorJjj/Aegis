package com.aegis.core.notifications;

import java.util.Map;
import java.util.UUID;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own notifications in their active organization (E9 story 5): what needs their decision
 * in the services they own. Nobody reads another person's.
 */
@RestController
@RequestMapping("/api/notifications")
@SecurityRequirement(name = "bearer-jwt")
public class NotificationController {

    private final OwnerNotificationStore store;

    public NotificationController(OwnerNotificationStore store) {
        this.store = store;
    }

    @GetMapping
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "Your notifications, newest first; only the unread ones with unread=true")
    public Page<OwnerNotification> list(
            @RequestParam(defaultValue = "false") boolean unread,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            Authentication authentication) {
        return store.findForUser(userId(authentication), unread, pageable);
    }

    @GetMapping("/unread-count")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "How many of your notifications are unread")
    public Map<String, Long> unreadCount(Authentication authentication) {
        return Map.of("unread", store.countUnread(userId(authentication)));
    }

    @PostMapping("/{id}/read")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "Mark one of your notifications read")
    public ResponseEntity<Void> markRead(@PathVariable UUID id, Authentication authentication) {
        if (!store.markRead(id, userId(authentication))) {
            throw new ApiExceptions.NotFound("Notification not found: " + id);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @PreAuthorize(InventoryRoles.READ)
    @Operation(summary = "Mark all your notifications read")
    public Map<String, Integer> markAllRead(Authentication authentication) {
        return Map.of("marked", store.markAllRead(userId(authentication)));
    }

    private static UUID userId(Authentication authentication) {
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException | NullPointerException notAUser) {
            throw new ApiExceptions.Forbidden("Only a signed-in user has notifications");
        }
    }
}
