package com.usermgmt.identity.audit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists audit entries in their own transaction. Kept in a separate bean from
 * {@link AuditService} so the {@code REQUIRES_NEW} proxy actually applies: an audit record is
 * committed independently of the operation being audited, so it survives a rollback of that
 * operation (and, conversely, a failed audit write cannot roll the operation back — callers
 * invoke this via {@link AuditService}, which swallows write failures).
 */
@Component
class AuditWriter {

    private final AuditEventRepository repository;

    AuditWriter(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void write(AuditEvent event) {
        repository.save(event);
    }
}
