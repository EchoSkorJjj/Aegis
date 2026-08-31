package com.usermgmt.identity.audit;

import com.usermgmt.identity.common.ClientIp;
import com.usermgmt.identity.user.User;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Records and queries the security audit trail.
 *
 * <p>Writing is intentionally forgiving: {@link Entry#log()} resolves the actor (from the
 * security context, unless set explicitly), the client IP and the user agent (from the current
 * request), then persists via {@link AuditWriter} in a separate transaction. Any failure to
 * write is logged and swallowed — auditing must never break the operation being audited.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditWriter writer;
    private final AuditEventRepository repository;
    private final MeterRegistry meterRegistry;

    public AuditService(AuditWriter writer, AuditEventRepository repository, MeterRegistry meterRegistry) {
        this.writer = writer;
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    /** Begins a fluent audit entry, e.g. {@code audit.event(LOGIN_FAILED).actorEmail(email).log();} */
    public Entry event(AuditEventType type) {
        return new Entry(type);
    }

    /** Filtered, paginated read of the trail (admin-facing). Any filter may be null. */
    public Page<AuditEvent> search(AuditEventType eventType, AuditOutcome outcome, String actorEmail,
                                   Instant from, Instant to, Pageable pageable) {
        List<Specification<AuditEvent>> specs = new ArrayList<>();
        if (eventType != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("eventType"), eventType));
        }
        if (outcome != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("outcome"), outcome));
        }
        if (actorEmail != null && !actorEmail.isBlank()) {
            String needle = actorEmail.trim().toLowerCase();
            specs.add((root, query, cb) -> cb.equal(cb.lower(root.get("actorEmail")), needle));
        }
        if (from != null) {
            specs.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
        }
        if (to != null) {
            specs.add((root, query, cb) -> cb.lessThanOrEqualTo(root.get("occurredAt"), to));
        }
        return repository.findAll(Specification.allOf(specs), pageable);
    }

    private void persist(Entry entry) {
        try {
            if (!entry.actorSet) {
                resolveActorFromSecurityContext(entry);
            }
            HttpServletRequest request = currentRequest();
            AuditEvent event = new AuditEvent(
                    UUID.randomUUID(),
                    Instant.now(),
                    entry.type,
                    entry.outcome,
                    entry.actorUserId,
                    entry.actorEmail,
                    entry.targetUserId,
                    clientIp(request),
                    truncate(header(request, "User-Agent"), 512),
                    entry.detail);
            Counter.builder("identity.audit.events")
                    .description("Security audit events recorded, by type and outcome")
                    .tag("type", entry.type.name())
                    .tag("outcome", entry.outcome.name())
                    .register(meterRegistry)
                    .increment();
            writer.write(event);
        } catch (RuntimeException ex) {
            // Auditing is best-effort: never let a trail write break the audited operation.
            log.warn("Failed to record audit event {}", entry.type, ex);
        }
    }

    private void resolveActorFromSecurityContext(Entry entry) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            entry.actorUserId = parseUuid(jwt.getSubject());
            entry.actorEmail = jwt.getClaimAsString("email");
        }
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private static String clientIp(HttpServletRequest request) {
        return truncate(ClientIp.of(request), 45);
    }

    private static String header(HttpServletRequest request, String name) {
        return request == null ? null : request.getHeader(name);
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Mutable, fluent accumulator for one audit entry; nothing is written until {@link #log()}. */
    public final class Entry {

        private final AuditEventType type;
        private AuditOutcome outcome;
        private UUID actorUserId;
        private String actorEmail;
        private UUID targetUserId;
        private String detail;
        private boolean actorSet;

        private Entry(AuditEventType type) {
            this.type = type;
            this.outcome = type.defaultOutcome();
        }

        public Entry outcome(AuditOutcome outcome) {
            this.outcome = outcome;
            return this;
        }

        /** The user who performed the action. */
        public Entry actor(User user) {
            this.actorUserId = user.getId();
            this.actorEmail = user.getEmail();
            this.actorSet = true;
            return this;
        }

        /** Actor known only by id (e.g. a self-service logout resolved from the refresh token). */
        public Entry actor(UUID userId) {
            this.actorUserId = userId;
            this.actorSet = true;
            return this;
        }

        /** Actor known only by email (e.g. a login attempt for an address with no matching user). */
        public Entry actorEmail(String email) {
            this.actorEmail = email;
            this.actorSet = true;
            return this;
        }

        /** The user the action targets, when different from the actor. */
        public Entry target(User user) {
            this.targetUserId = user.getId();
            return this;
        }

        public Entry target(UUID userId) {
            this.targetUserId = userId;
            return this;
        }

        public Entry detail(String detail) {
            this.detail = detail;
            return this;
        }

        /** Records the entry. Best-effort: write failures are logged, not thrown. */
        public void log() {
            persist(this);
        }
    }
}
