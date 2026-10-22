package com.aegis.core.actions.dashboard;

import java.time.LocalDate;
import java.util.List;

import com.aegis.core.actions.dashboard.RiskPosture.Snapshot;

/** Daily organization totals only: these are observations, not persisted findings or reconstructed history. */
public interface PostureSnapshotStore {
    void save(Snapshot snapshot);

    List<Snapshot> between(LocalDate from, LocalDate to);
}
