package com.aegis.core.actions.dashboard;

import java.time.LocalDate;
import java.util.List;

import com.aegis.core.actions.dashboard.RiskPosture.Breakdown;
import com.aegis.core.actions.dashboard.RiskPosture.Snapshot;

/** Daily observations of the organization, its services and its owners; never persisted findings. */
public interface PostureSnapshotStore {
    record RecordedSnapshot(Snapshot total, List<Breakdown> services, List<Breakdown> owners) {
        public RecordedSnapshot {
            services = List.copyOf(services);
            owners = List.copyOf(owners);
        }
    }

    /** Organization totals and available group observations from the same database reading. */
    record History(List<Snapshot> trend, List<RecordedSnapshot> days) {
        public History {
            trend = List.copyOf(trend);
            days = List.copyOf(days);
        }
    }

    /** Replace the whole day's observation atomically, only when it is at least as recent as the kept one. */
    void save(RecordedSnapshot snapshot);

    List<Snapshot> between(LocalDate from, LocalDate to);

    /** Only days with recorded breakdowns. Older totals-only observations are not guessed or backfilled. */
    List<RecordedSnapshot> recordedBetween(LocalDate from, LocalDate to);

    History historyBetween(LocalDate from, LocalDate to);
}
