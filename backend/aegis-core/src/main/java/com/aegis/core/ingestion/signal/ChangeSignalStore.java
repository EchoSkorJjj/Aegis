package com.aegis.core.ingestion.signal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Read-only persistence port for the unified {@link ChangeSignal} projection. */
public interface ChangeSignalStore {

    Optional<ChangeSignal> findById(UUID id);

    /** A page of signals across every source, most recently occurred first by default. */
    Page<ChangeSignal> findPage(Pageable pageable);
}
