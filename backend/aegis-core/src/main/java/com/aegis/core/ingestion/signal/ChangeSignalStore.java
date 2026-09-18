package com.aegis.core.ingestion.signal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Read-only persistence port for the unified {@link ChangeSignal} projection. */
public interface ChangeSignalStore {

    Optional<ChangeSignal> findById(UUID id);

    /** A filtered page of signals across every source, most recently occurred first by default. */
    Page<ChangeSignal> findPage(ChangeSignalFilter filter, Pageable pageable);

    /** Distinct source ids present, sorted — powers the source filter select. */
    List<String> findSources();

    /** Distinct severities present (non-null), sorted — powers the severity filter select. */
    List<String> findSeverities();

    /** Distinct package ecosystems present across advisories, sorted — powers the ecosystem filter select. */
    List<String> findEcosystems();
}
