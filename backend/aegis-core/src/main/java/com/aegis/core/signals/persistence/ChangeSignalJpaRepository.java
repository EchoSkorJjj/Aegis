package com.aegis.core.signals.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.aegis.core.signals.ChangeSignal.SignalType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

interface ChangeSignalJpaRepository
        extends JpaRepository<ChangeSignalEntity, UUID>, JpaSpecificationExecutor<ChangeSignalEntity> {

    /** A vulnerability row's title is its canonical id (V12). */
    List<ChangeSignalEntity> findBySignalTypeAndTitleIn(SignalType signalType, Collection<String> titles);

    @Query("select distinct s.sourceId from ChangeSignalEntity s where s.sourceId is not null order by s.sourceId")
    List<String> findDistinctSources();

    // Feeds store severity in mixed case (HIGH vs high); upper-case + dedupe so the filter select shows
    // one clean option per level. The list filter matches case-insensitively, so this stays correct.
    @Query("select distinct upper(s.severity) from ChangeSignalEntity s "
            + "where s.severity is not null order by upper(s.severity)")
    List<String> findDistinctSeverities();
}
