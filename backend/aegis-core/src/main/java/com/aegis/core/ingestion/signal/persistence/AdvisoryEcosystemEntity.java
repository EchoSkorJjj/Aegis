package com.aegis.core.ingestion.signal.persistence;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

/**
 * Read-only mapping onto the {@code advisory_ecosystems} view — the (canonical vulnerability id,
 * package ecosystem) pairs that back the change-signals ecosystem filter and facet. Never written:
 * the view is derived from {@code advisories} + {@code advisory_affected_products}. Correlated against
 * a signal's {@code title} (which is the canonical id for advisories), so an EXISTS on this view tells
 * whether a signal touches a given ecosystem, folded across every advisory sharing the canonical id.
 */
@Entity
@Immutable
@Table(name = "advisory_ecosystems")
@IdClass(AdvisoryEcosystemEntity.Key.class)
class AdvisoryEcosystemEntity {

    // As with ChangeSignalEntity, view columns report as nullable in Postgres' information_schema, so
    // no @Column(nullable = false) here — it would fail Hibernate's schema validation against the view.
    @Id
    @Column(name = "canonical_id")
    private String canonicalId;

    @Id
    @Column(name = "ecosystem")
    private String ecosystem;

    protected AdvisoryEcosystemEntity() {
        // Required by JPA.
    }

    String getCanonicalId() {
        return canonicalId;
    }

    String getEcosystem() {
        return ecosystem;
    }

    /** Composite primary key: a canonical id can touch several ecosystems, and vice versa. */
    static class Key implements Serializable {

        private String canonicalId;
        private String ecosystem;

        protected Key() {
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(canonicalId, key.canonicalId) && Objects.equals(ecosystem, key.ecosystem);
        }

        @Override
        public int hashCode() {
            return Objects.hash(canonicalId, ecosystem);
        }
    }
}
