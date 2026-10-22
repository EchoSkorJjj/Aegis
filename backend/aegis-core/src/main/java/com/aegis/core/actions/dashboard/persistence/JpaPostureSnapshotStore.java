package com.aegis.core.actions.dashboard.persistence;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.aegis.core.actions.dashboard.PostureSnapshotStore;
import com.aegis.core.actions.dashboard.RiskPosture.Breakdown;
import com.aegis.core.actions.dashboard.RiskPosture.Snapshot;
import com.aegis.core.platform.tenant.CurrentOrg;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaPostureSnapshotStore implements PostureSnapshotStore {
    @PersistenceContext private EntityManager entityManager;
    private final CurrentOrg currentOrg;

    JpaPostureSnapshotStore(CurrentOrg currentOrg) {
        this.currentOrg = currentOrg;
    }

    @Override
    @Transactional
    public void save(RecordedSnapshot recorded) {
        UUID org = scope();
        Snapshot snapshot = recorded.total();
        // The parent row serializes competing captures. Children change only if this capture wins, and the
        // transaction commits totals and both breakdowns together (or rolls the entire observation back).
        int changed = entityManager.createNativeQuery("""
                insert into core.risk_posture_snapshots
                    (org_id, day, captured_at, open_items, critical_items, high_items, breakdowns_recorded)
                values (:org, :day, :at, :open, :critical, :high, true)
                on conflict (org_id, day) do update set captured_at = excluded.captured_at,
                    open_items = excluded.open_items, critical_items = excluded.critical_items,
                    high_items = excluded.high_items, breakdowns_recorded = true
                where risk_posture_snapshots.captured_at <= excluded.captured_at
                """)
                .setParameter("org", org).setParameter("day", snapshot.day())
                .setParameter("at", snapshot.capturedAt().atOffset(java.time.ZoneOffset.UTC))
                .setParameter("open", snapshot.openItems()).setParameter("critical", snapshot.criticalItems())
                .setParameter("high", snapshot.highItems()).executeUpdate();
        if (changed == 0) {
            return;
        }
        entityManager.createNativeQuery("delete from core.risk_posture_breakdowns where org_id = :org and day = :day")
                .setParameter("org", org).setParameter("day", snapshot.day()).executeUpdate();
        entityManager.unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    insert into core.risk_posture_breakdowns
                        (org_id, day, dimension, subject_id, subject_name, open_items, critical_items, high_items, top_score)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (Breakdown service : recorded.services()) {
                    add(insert, org, snapshot.day(), "SERVICE", service);
                }
                for (Breakdown owner : recorded.owners()) {
                    add(insert, org, snapshot.day(), owner.id() == null ? "UNASSIGNED" : "OWNER", owner);
                }
                insert.executeBatch();
            }
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<Snapshot> between(LocalDate from, LocalDate to) {
        UUID org = scope();
        return totals(org, from, to, false);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public History historyBetween(LocalDate from, LocalDate to) {
        return new History(between(from, to), recordedBetween(from, to));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @SuppressWarnings("unchecked")
    public List<RecordedSnapshot> recordedBetween(LocalDate from, LocalDate to) {
        UUID org = scope();
        List<Snapshot> totals = totals(org, from, to, true);
        if (totals.isEmpty()) {
            return List.of();
        }
        NativeQuery<Object[]> query = entityManager.createNativeQuery("""
                select day, dimension, subject_id, subject_name, open_items, critical_items, high_items, top_score
                from core.risk_posture_breakdowns
                where org_id = :org and day between :from and :to
                order by day, open_items desc, critical_items desc, lower(subject_name), subject_id nulls first
                """).unwrap(NativeQuery.class);
        query.addScalar("day", LocalDate.class).addScalar("dimension", String.class)
                .addScalar("subject_id", UUID.class).addScalar("subject_name", String.class)
                .addScalar("open_items", Integer.class).addScalar("critical_items", Integer.class)
                .addScalar("high_items", Integer.class).addScalar("top_score", Double.class);
        List<Object[]> rows = query.setParameter("org", org).setParameter("from", from).setParameter("to", to)
                .getResultList();
        Map<LocalDate, List<Breakdown>> services = new HashMap<>();
        Map<LocalDate, List<Breakdown>> owners = new HashMap<>();
        for (Object[] row : rows) {
            Breakdown breakdown = new Breakdown((UUID) row[2], (String) row[3], (Integer) row[4], (Integer) row[5],
                    (Integer) row[6], (Double) row[7]);
            Map<LocalDate, List<Breakdown>> groups = "SERVICE".equals(row[1]) ? services : owners;
            groups.computeIfAbsent((LocalDate) row[0], day -> new ArrayList<>()).add(breakdown);
        }
        return totals.stream().map(total -> new RecordedSnapshot(total,
                services.getOrDefault(total.day(), List.of()), owners.getOrDefault(total.day(), List.of()))).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Snapshot> totals(UUID org, LocalDate from, LocalDate to, boolean detailedOnly) {
        NativeQuery<Object[]> query = entityManager.createNativeQuery("""
                select day, captured_at, open_items, critical_items, high_items from core.risk_posture_snapshots
                where org_id = :org and day between :from and :to
                    and (not :detailedOnly or breakdowns_recorded) order by day
                """)
                .unwrap(NativeQuery.class);
        query.addScalar("day", LocalDate.class).addScalar("captured_at", Instant.class)
                .addScalar("open_items", Integer.class).addScalar("critical_items", Integer.class)
                .addScalar("high_items", Integer.class);
        List<Object[]> rows = query.setParameter("org", org).setParameter("from", from)
                .setParameter("to", to).setParameter("detailedOnly", detailedOnly).getResultList();
        return rows.stream().map(row -> new Snapshot((LocalDate) row[0], (Instant) row[1], ((Number) row[2]).intValue(),
                ((Number) row[3]).intValue(), ((Number) row[4]).intValue())).toList();
    }

    private static void add(PreparedStatement insert, UUID org, LocalDate day, String dimension, Breakdown row)
            throws SQLException {
        insert.setObject(1, org);
        insert.setDate(2, Date.valueOf(day));
        insert.setString(3, dimension);
        insert.setObject(4, row.id());
        insert.setString(5, row.name());
        insert.setInt(6, row.openItems());
        insert.setInt(7, row.criticalItems());
        insert.setInt(8, row.highItems());
        if (row.topScore() == null) {
            insert.setNull(9, Types.DOUBLE);
        } else {
            insert.setDouble(9, row.topScore());
        }
        insert.addBatch();
    }

    private UUID scope() {
        UUID org = currentOrg.require();
        entityManager.createNativeQuery("select set_config('aegis.current_org', :org, true)")
                .setParameter("org", org.toString()).getSingleResult();
        return org;
    }
}
