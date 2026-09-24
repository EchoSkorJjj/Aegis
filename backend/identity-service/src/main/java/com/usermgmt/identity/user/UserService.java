package com.usermgmt.identity.user;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import com.usermgmt.identity.audit.AuditEventType;
import com.usermgmt.identity.audit.AuditService;
import com.usermgmt.identity.common.ApiExceptions;
import com.usermgmt.identity.org.OrganizationMember;
import com.usermgmt.identity.org.OrganizationMemberRepository;
import com.usermgmt.identity.user.UserDtos.UserResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User administration (ADR 0001). Two scopes drive every admin method:
 * <ul>
 *   <li><b>Platform</b> ({@code orgScope == null}, a PLATFORM_ADMIN): acts on identities across all
 *       tenants; roles read/written are the platform-scoped {@code user_roles}; delete removes the
 *       identity.</li>
 *   <li><b>Organization</b> ({@code orgScope != null}, an ORG_ADMIN): acts only on members of that org
 *       via {@code organization_members}; roles read/written are that membership's org roles; delete
 *       removes the membership (the identity survives). A target outside the org is <em>not found</em>.</li>
 * </ul>
 * A response's {@code roles} are the user's <em>effective</em> roles in the viewed org (platform roles
 * plus the membership's roles); {@code orgId} is the viewed org (or, at platform scope, the user's
 * last-active org).
 */
@Service
public class UserService {

    private final UserRepository users;
    private final OrganizationMemberRepository members;
    private final AuditService audit;

    public UserService(UserRepository users, OrganizationMemberRepository members, AuditService audit) {
        this.users = users;
        this.members = members;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public User get(UUID id) {
        return users.findById(id).orElseThrow(() -> new ApiExceptions.NotFound("User not found: " + id));
    }

    /** A user's own profile with its effective roles in {@code viewOrg} (their active org; may be null). */
    @Transactional(readOnly = true)
    public UserResponse getSelf(UUID id, UUID viewOrg) {
        return describe(get(id), viewOrg);
    }

    /** Loads a user for an admin action within {@code orgScope} (see the class doc for the two scopes). */
    @Transactional(readOnly = true)
    public UserResponse getInScope(UUID id, UUID orgScope) {
        return describe(requireInScope(id, orgScope), orgScope);
    }

    private User requireInScope(UUID id, UUID orgScope) {
        User user = get(id);
        if (orgScope != null && !members.existsByOrgIdAndUserId(orgScope, id)) {
            throw new ApiExceptions.NotFound("User not found: " + id);
        }
        return user;
    }

    /**
     * Like {@link #requireInScope} but also forbids an org admin from modifying another organization
     * admin (ADR 0001, "domain roles only"): at org scope, a target holding ORG_ADMIN may be changed
     * only by a platform admin — so no org admin can demote, disable, or remove another. Platform
     * scope (null) is unrestricted. Reads are not gated (an org admin may still see other admins).
     */
    private User requireModifiable(UUID id, UUID orgScope) {
        User user = requireInScope(id, orgScope);
        if (orgScope != null
                && members.findByOrgIdAndUserId(orgScope, id)
                        .map(m -> m.getRoles().contains(Role.ORG_ADMIN))
                        .orElse(false)) {
            throw new ApiExceptions.Forbidden("Only a platform admin can modify an organization admin");
        }
        return user;
    }

    /**
     * Admin listing. Platform scope lists all identities; an org scope lists only that org's members.
     * Optional filters: a case-insensitive substring on email/display name, a role the user holds in
     * the viewed scope (e.g. {@code SERVICE_OWNER} for aegis-core's owner picker), and a createdAt window.
     */
    @Transactional(readOnly = true)
    public Page<UserResponse> list(
            UUID orgScope, String search, Role role, Instant createdAfter, Instant createdBefore, Pageable pageable) {
        List<Specification<User>> specs = new ArrayList<>();
        if (orgScope != null) {
            specs.add((root, query, cb) -> {
                var membership = query.subquery(UUID.class);
                var m = membership.from(OrganizationMember.class);
                membership.select(m.get("userId"))
                        .where(cb.equal(m.get("orgId"), orgScope), cb.equal(m.get("userId"), root.get("id")));
                return cb.exists(membership);
            });
        }
        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim().toLowerCase() + "%";
            specs.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("email")), like),
                    cb.like(cb.lower(root.get("displayName")), like)));
        }
        if (role != null) {
            specs.add(role.isPlatformScoped() ? hasPlatformRole(role) : hasOrgRole(role, orgScope));
        }
        if (createdAfter != null) {
            specs.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), createdAfter));
        }
        if (createdBefore != null) {
            specs.add((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), createdBefore));
        }
        return users.findAll(Specification.allOf(specs), pageable).map(u -> describe(u, orgScope));
    }

    /** A platform-scoped role lives on the identity itself ({@code user_roles}). */
    private static Specification<User> hasPlatformRole(Role role) {
        return (root, query, cb) -> cb.isMember(role, root.<Set<Role>>get("roles"));
    }

    /**
     * An org-scoped role lives on a membership ({@code organization_member_roles}). Within an org scope
     * the membership must be in that org — the same roles {@link #describe} reports for it; at platform
     * scope (a platform admin's cross-org view) a membership in any org counts.
     */
    private static Specification<User> hasOrgRole(Role role, UUID orgScope) {
        return (root, query, cb) -> {
            var membership = query.subquery(UUID.class);
            var m = membership.from(OrganizationMember.class);
            var memberRole = m.join("roles");
            var sameUser = cb.equal(m.get("userId"), root.get("id"));
            var holdsRole = cb.equal(memberRole, role);
            membership.select(m.get("userId")).where(orgScope == null
                    ? cb.and(sameUser, holdsRole)
                    : cb.and(sameUser, holdsRole, cb.equal(m.get("orgId"), orgScope)));
            return cb.exists(membership);
        };
    }

    @Transactional
    public UserResponse update(UUID id, UUID orgScope, boolean self, String displayName, Boolean enabled) {
        // Editing your own profile is always allowed (the controller already authorized it). The
        // "can't modify another org admin" guard applies only when an admin edits someone else —
        // otherwise an org admin could never even change their own display name.
        User user = self ? get(id) : requireModifiable(id, orgScope);
        StringBuilder detail = new StringBuilder();
        if (displayName != null) {
            user.setDisplayName(displayName);
            detail.append("displayName updated");
        }
        if (enabled != null) {
            user.setEnabled(enabled);
            if (detail.length() > 0) {
                detail.append("; ");
            }
            detail.append("enabled=").append(enabled);
        }
        audit.event(AuditEventType.USER_UPDATED).target(user)
                .detail(detail.length() == 0 ? "no changes" : detail.toString()).log();
        return describe(user, orgScope);
    }

    /**
     * Sets a user's roles. Platform scope replaces the identity's platform roles ({@code user_roles});
     * org scope replaces the roles on the caller org's membership, leaving the identity and other orgs
     * untouched.
     */
    @Transactional
    public UserResponse updateRoles(UUID id, UUID orgScope, Set<Role> roles) {
        User user = requireModifiable(id, orgScope);
        if (orgScope == null) {
            Set<Role> before = new TreeSet<>(user.getRoles());
            user.setRoles(new HashSet<>(roles));
            audit.event(AuditEventType.USER_ROLES_CHANGED).target(user)
                    .detail("platform roles: " + before + " -> " + new TreeSet<>(roles)).log();
        } else {
            OrganizationMember membership = members.findByOrgIdAndUserId(orgScope, id)
                    .orElseThrow(() -> new ApiExceptions.NotFound("User not found: " + id));
            Set<Role> orgRoles = roles.stream().filter(r -> !r.isPlatformScoped()).collect(Collectors.toSet());
            Set<Role> before = new TreeSet<>(membership.getRoles());
            membership.setRoles(orgRoles);
            audit.event(AuditEventType.USER_ROLES_CHANGED).target(user)
                    .detail("org " + orgScope + " roles: " + before + " -> " + new TreeSet<>(orgRoles)).log();
        }
        return describe(user, orgScope);
    }

    /**
     * Removes a user. Platform scope deletes the identity (cascading its memberships); org scope removes
     * only the caller org's membership, leaving the identity and other orgs intact.
     */
    @Transactional
    public void delete(UUID id, UUID orgScope) {
        User user = requireModifiable(id, orgScope);
        if (orgScope == null) {
            String email = user.getEmail();
            users.delete(user);
            audit.event(AuditEventType.USER_DELETED).target(id).detail("email=" + email).log();
        } else {
            members.findByOrgIdAndUserId(orgScope, id).ifPresent(members::delete);
            audit.event(AuditEventType.USER_REMOVED_FROM_ORG).target(user).detail("org=" + orgScope).log();
        }
    }

    /** A response showing the user's effective roles in {@code viewOrg} (platform roles + that org's membership roles). */
    public UserResponse describe(User user, UUID viewOrg) {
        UUID orgId = (viewOrg != null) ? viewOrg : user.getLastActiveOrgId();
        return UserResponse.of(user, effectiveRoles(user, viewOrg), orgId);
    }

    private Set<Role> effectiveRoles(User user, UUID orgId) {
        Set<Role> roles = user.getRoles().stream()
                .filter(Role::isPlatformScoped)
                .collect(Collectors.toCollection(HashSet::new));
        if (orgId != null) {
            members.findByOrgIdAndUserId(orgId, user.getId()).ifPresent(m -> roles.addAll(m.getRoles()));
        }
        return roles;
    }
}
