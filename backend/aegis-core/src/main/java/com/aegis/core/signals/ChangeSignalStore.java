package com.aegis.core.signals;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Read-only persistence port for the unified {@link ChangeSignal} projection. */
public interface ChangeSignalStore {

    Optional<ChangeSignal> findById(UUID id);

    /**
     * The vulnerability signal for each canonical id (a CVE, or a GHSA with none) that has one — the view
     * keeps one row per vulnerability however many feeds reported it.
     */
    Map<String, UUID> findVulnerabilityIds(Collection<String> canonicalIds);

    /** A filtered page of signals across every source, most recently occurred first by default. */
    Page<ChangeSignal> findPage(ChangeSignalFilter filter, Pageable pageable);

    /** Distinct source ids present, sorted — powers the source filter select. */
    List<String> findSources();

    /** Distinct severities present (non-null), sorted — powers the severity filter select. */
    List<String> findSeverities();

    /** Distinct package ecosystems present across advisories, sorted — powers the ecosystem filter select. */
    List<String> findEcosystems();
}
