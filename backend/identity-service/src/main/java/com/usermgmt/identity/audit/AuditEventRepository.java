package com.usermgmt.identity.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/**
 * Insert + query only. The application never updates or deletes audit rows (and the database
 * blocks it too); {@link JpaSpecificationExecutor} backs the filtered admin search.
 */
public interface AuditEventRepository
        extends JpaRepository<AuditEvent, UUID>, JpaSpecificationExecutor<AuditEvent> {
}
