package com.aegis.core.ingestion.signal.persistence;

import java.util.Optional;
import java.util.UUID;

import com.aegis.core.ingestion.signal.ChangeSignal;
import com.aegis.core.ingestion.signal.ChangeSignalStore;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    public Page<ChangeSignal> findPage(Pageable pageable) {
        return signals.findAll(pageable).map(JpaChangeSignalStore::toChangeSignal);
    }

    private static ChangeSignal toChangeSignal(ChangeSignalEntity entity) {
        return new ChangeSignal(
                entity.getId(), entity.getSignalType(), entity.getSourceId(), entity.getExternalId(),
                entity.getTitle(), entity.getSummary(), entity.getSeverity(), entity.getUrl(),
                entity.getOccurredAt(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
