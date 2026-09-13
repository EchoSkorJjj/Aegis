package com.usermgmt.identity.user;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.usermgmt.identity.audit.AuditEventType;
import com.usermgmt.identity.audit.AuditService;
import com.usermgmt.identity.common.ApiExceptions;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;
    private final AuditService audit;

    public UserService(UserRepository users, AuditService audit) {
        this.users = users;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public User get(UUID id) {
        return users.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFound("User not found: " + id));
    }

    /**
     * Admin listing with optional filters: a case-insensitive substring match on email or
     * display name, and a {@code createdAt} window. Any filter may be null; ordering and paging
     * come from {@code pageable}.
     */
    @Transactional(readOnly = true)
    public Page<User> list(String search, Instant createdAfter, Instant createdBefore, Pageable pageable) {
        List<Specification<User>> specs = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim().toLowerCase() + "%";
            specs.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("email")), like),
                    cb.like(cb.lower(root.get("displayName")), like)));
        }
        if (createdAfter != null) {
            specs.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), createdAfter));
        }
        if (createdBefore != null) {
            specs.add((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), createdBefore));
        }
        return users.findAll(Specification.allOf(specs), pageable);
    }

    @Transactional
    public User update(UUID id, String displayName, Boolean enabled) {
        User user = get(id);
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
        return user; // managed entity; flushed on transaction commit
    }

    @Transactional
    public User updateRoles(UUID id, Set<Role> roles) {
        User user = get(id);
        Set<Role> before = new TreeSet<>(user.getRoles());
        user.setRoles(new HashSet<>(roles));
        audit.event(AuditEventType.USER_ROLES_CHANGED).target(user)
                .detail("roles: " + before + " -> " + new TreeSet<>(roles)).log();
        return user;
    }

    @Transactional
    public void delete(UUID id) {
        User user = get(id); // throws NotFound if missing
        String email = user.getEmail();
        users.delete(user);
        audit.event(AuditEventType.USER_DELETED).target(id).detail("email=" + email).log();
    }
}
