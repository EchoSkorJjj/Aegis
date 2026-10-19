package com.aegis.core.signals.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Read-only access to the {@code advisory_ecosystems} view — powers the ecosystem filter select. */
interface AdvisoryEcosystemJpaRepository
        extends JpaRepository<AdvisoryEcosystemEntity, AdvisoryEcosystemEntity.Key> {

    // Already lower-cased by the view, so the select shows one clean option per ecosystem; the list
    // filter lower-cases its argument to match.
    @Query("select distinct e.ecosystem from AdvisoryEcosystemEntity e order by e.ecosystem")
    List<String> findDistinctEcosystems();
}
