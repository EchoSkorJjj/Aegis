package com.usermgmt.identity.user;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;

import com.usermgmt.identity.auth.account.AccountService;
import com.usermgmt.identity.common.ApiExceptions;
import com.usermgmt.identity.user.UserDtos.InviteUserRequest;
import com.usermgmt.identity.user.UserDtos.UpdateRolesRequest;
import com.usermgmt.identity.user.UserDtos.UpdateUserRequest;
import com.usermgmt.identity.user.UserDtos.UserResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
@SecurityRequirement(name = "bearer-jwt")
public class UserController {

    /**
     * May manage users. An org admin is confined to its own organization (see {@link #callerScope});
     * a platform admin acts across all tenants.
     */
    private static final String MANAGE = "hasAnyRole('ORG_ADMIN','PLATFORM_ADMIN')";

    /**
     * May read the member directory: any authenticated user. Org-scoping is applied per request
     * ({@link #callerScope}), so a member sees only their own org and an org-less user gets no
     * directory. Mutations still require {@link #MANAGE}.
     */
    private static final String READ = "isAuthenticated()";

    private final UserService service;
    private final AccountService accounts;

    public UserController(UserService service, AccountService accounts) {
        this.service = service;
        this.accounts = accounts;
    }

    /** Provisions an account into the caller's org with the given roles; emails a set-password link. */
    @PostMapping
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse invite(@Valid @RequestBody InviteUserRequest req, @AuthenticationPrincipal Jwt jwt) {
        assertCanAssign(req.roles(), jwt);
        UUID scope = callerScope(jwt);
        return service.describe(accounts.inviteUser(req.email(), req.displayName(), scope, req.roles()), scope);
    }

    /** The caller's own profile (effective roles in their active org), resolved from the token. */
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return service.getSelf(UUID.fromString(jwt.getSubject()), activeOrg(jwt));
    }

    @GetMapping
    @PreAuthorize(READ)
    public Page<UserResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal Jwt jwt) {
        return service.list(callerScope(jwt), search, from, to, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public UserResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        if (id.toString().equals(jwt.getSubject())) {
            return service.getSelf(id, activeOrg(jwt)); // self
        }
        return service.getInScope(id, callerScope(jwt)); // admin, org-scoped
    }

    @PutMapping("/{id}")
    @PreAuthorize(MANAGE + " or #id.toString() == authentication.name")
    public UserResponse update(
            @PathVariable UUID id, @Valid @RequestBody UpdateUserRequest req, @AuthenticationPrincipal Jwt jwt) {
        boolean self = id.toString().equals(jwt.getSubject());
        // Only an admin acting on another account may flip 'enabled'; a self-edit never toggles its
        // own account (so it also never trips the "can't modify an org admin" guard in the service).
        Boolean enabled = (isAnyAdmin(jwt) && !self) ? req.enabled() : null;
        UUID scope = self ? activeOrg(jwt) : callerScope(jwt);
        return service.update(id, scope, self, req.displayName(), enabled);
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize(MANAGE)
    public UserResponse updateRoles(
            @PathVariable UUID id, @Valid @RequestBody UpdateRolesRequest req, @AuthenticationPrincipal Jwt jwt) {
        assertCanAssign(req.roles(), jwt);
        return service.updateRoles(id, callerScope(jwt), req.roles());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(MANAGE)
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        service.delete(id, callerScope(jwt));
        return ResponseEntity.noContent().build();
    }

    /** The organization an admin action is confined to; null for a platform-level admin (all tenants). */
    private static UUID callerScope(Jwt jwt) {
        if (isPlatformLevel(jwt)) {
            return null;
        }
        String org = jwt.getClaimAsString("org");
        if (org == null) {
            throw new ApiExceptions.Forbidden("No organization context");
        }
        return UUID.fromString(org);
    }

    /** The caller's active organization from the token, or null (an org-less user / platform admin). */
    private static UUID activeOrg(Jwt jwt) {
        String org = jwt.getClaimAsString("org");
        return (org == null) ? null : UUID.fromString(org);
    }

    private static boolean isPlatformLevel(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("PLATFORM_ADMIN");
    }

    private static boolean isAnyAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && (roles.contains("PLATFORM_ADMIN") || roles.contains("ORG_ADMIN"));
    }

    /**
     * Org admins may assign only the org-scoped domain roles — not ORG_ADMIN or platform roles
     * (ADR 0001, "domain roles only": org-admin status comes only from creating an organization or
     * from a platform admin). A platform admin may assign anything.
     */
    private static void assertCanAssign(Set<Role> roles, Jwt jwt) {
        if (roles == null || isPlatformLevel(jwt)) {
            return;
        }
        if (roles.contains(Role.PLATFORM_ADMIN) || roles.contains(Role.ORG_ADMIN)) {
            throw new ApiExceptions.Forbidden("Only a platform admin may assign admin roles");
        }
    }
}
