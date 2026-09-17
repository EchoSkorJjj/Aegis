package com.aegis.core.ingestion.signal.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.aegis.core.ingestion.signal.ChangeSignal;
import com.aegis.core.ingestion.signal.ChangeSignalFilter;
import com.aegis.core.ingestion.signal.ChangeSignalStore;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

/** JPA adapter for {@link ChangeSignalStore}, reading the {@code change_signals} view. */
@Repository
class JpaChangeSignalStore implements ChangeSignalStore {

    private final ChangeSignalJpaRepository signals;

    JpaChangeSignalStore(ChangeSignalJpaRepository signals) {
        this.signals = signals;
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
