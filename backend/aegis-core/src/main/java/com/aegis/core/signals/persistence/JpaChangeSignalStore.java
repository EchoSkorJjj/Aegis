package com.aegis.core.signals.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.criteria.Subquery;

import com.aegis.core.signals.ChangeSignal;
import com.aegis.core.signals.ChangeSignalFilter;
import com.aegis.core.signals.ChangeSignalStore;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

/** JPA adapter for {@link ChangeSignalStore}, reading the {@code change_signals} view. */
@Repository
class JpaChangeSignalStore implements ChangeSignalStore {

    private final ChangeSignalJpaRepository signals;
    private final AdvisoryEcosystemJpaRepository ecosystems;

    JpaChangeSignalStore(ChangeSignalJpaRepository signals, AdvisoryEcosystemJpaRepository ecosystems) {
        this.signals = signals;
        this.ecosystems = ecosystems;
    }

    @Override
    public Optional<ChangeSignal> findById(UUID id) {
        return signals.findById(id).map(JpaChangeSignalStore::toChangeSignal);
    }

    @Override
    public Page<ChangeSignal> findPage(ChangeSignalFilter filter, Pageable pageable) {
        List<Specification<ChangeSignalEntity>> specs = new ArrayList<>();
        if (filter.type() != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("signalType"), filter.type()));
        }
        if (hasText(filter.severity())) {
            String severity = filter.severity().trim().toLowerCase(Locale.ROOT);
            specs.add((root, query, cb) -> cb.equal(cb.lower(root.get("severity")), severity));
        }
        if (hasText(filter.source())) {
            String source = filter.source().trim().toLowerCase(Locale.ROOT);
            specs.add((root, query, cb) -> cb.equal(cb.lower(root.get("sourceId")), source));
        }
        if (hasText(filter.search())) {
            String like = "%" + filter.search().trim().toLowerCase(Locale.ROOT) + "%";
            specs.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("title")), like),
                    cb.like(cb.lower(root.get("externalId")), like)));
        }
        if (hasText(filter.ecosystem())) {
            // Keep only signals whose canonical id touches this ecosystem (npm, PyPI, …). Correlates on
            // the signal's title (= canonical id for advisories), so vendor/news signals — whose titles
            // are headlines, not canonical ids — never match, i.e. an ecosystem filter implies advisories.
            // EXISTS (not IN) so a popular ecosystem's thousands of advisories stay a semi-join, not a list.
            String ecosystem = filter.ecosystem().trim().toLowerCase(Locale.ROOT);
            specs.add((root, query, cb) -> {
                Subquery<Integer> present = query.subquery(Integer.class);
                var affected = present.from(AdvisoryEcosystemEntity.class);
                present.select(cb.literal(1));
                present.where(
                        cb.equal(affected.get("canonicalId"), root.get("title")),
                        cb.equal(affected.get("ecosystem"), ecosystem));
                return cb.exists(present);
            });
        }
        if (filter.from() != null) {
            specs.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), filter.from()));
        }
        if (filter.to() != null) {
            specs.add((root, query, cb) -> cb.lessThanOrEqualTo(root.get("occurredAt"), filter.to()));
        }
        Page<ChangeSignalEntity> page = specs.isEmpty()
                ? signals.findAll(pageable)
                : signals.findAll(Specification.allOf(specs), pageable);
        return page.map(JpaChangeSignalStore::toChangeSignal);
    }

    @Override
    public List<String> findSources() {
        return signals.findDistinctSources();
    }

    @Override
    public List<String> findSeverities() {
        return signals.findDistinctSeverities();
    }

    @Override
    public List<String> findEcosystems() {
        return ecosystems.findDistinctEcosystems();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static ChangeSignal toChangeSignal(ChangeSignalEntity entity) {
        return new ChangeSignal(
                entity.getId(), entity.getSignalType(), entity.getSourceId(), entity.getExternalId(),
                entity.getTitle(), entity.getSummary(), entity.getSeverity(), entity.getUrl(),
                entity.getOccurredAt(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
