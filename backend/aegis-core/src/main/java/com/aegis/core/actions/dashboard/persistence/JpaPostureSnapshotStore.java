package com.aegis.core.actions.dashboard.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.aegis.core.actions.dashboard.PostureSnapshotStore;
import com.aegis.core.actions.dashboard.RiskPosture.Snapshot;
import com.aegis.core.platform.tenant.CurrentOrg;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
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
    public void save(Snapshot snapshot) {
        UUID org = scope();
        entityManager.createNativeQuery("""
                insert into core.risk_posture_snapshots (org_id, day, captured_at, open_items, critical_items, high_items)
                values (:org, :day, :at, :open, :critical, :high)
                on conflict (org_id, day) do update set captured_at = excluded.captured_at,
                    open_items = excluded.open_items, critical_items = excluded.critical_items,
                    high_items = excluded.high_items
                where risk_posture_snapshots.captured_at <= excluded.captured_at
                """)
                .setParameter("org", org).setParameter("day", snapshot.day())
                .setParameter("at", snapshot.capturedAt().atOffset(java.time.ZoneOffset.UTC))
                .setParameter("open", snapshot.openItems()).setParameter("critical", snapshot.criticalItems())
                .setParameter("high", snapshot.highItems()).executeUpdate();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Snapshot> between(LocalDate from, LocalDate to) {
        UUID org = scope();
        NativeQuery<Object[]> query = entityManager.createNativeQuery("""
                select day, captured_at, open_items, critical_items, high_items from core.risk_posture_snapshots
                where org_id = :org and day between :from and :to order by day
                """)
                .unwrap(NativeQuery.class);
        query.addScalar("day", LocalDate.class).addScalar("captured_at", Instant.class)
                .addScalar("open_items", Integer.class).addScalar("critical_items", Integer.class)
                .addScalar("high_items", Integer.class);
        List<Object[]> rows = query.setParameter("org", org).setParameter("from", from)
                .setParameter("to", to).getResultList();
        return rows.stream().map(row -> new Snapshot((LocalDate) row[0], (Instant) row[1], ((Number) row[2]).intValue(),
                ((Number) row[3]).intValue(), ((Number) row[4]).intValue())).toList();
    }

    private UUID scope() {
        UUID org = currentOrg.require();
        entityManager.createNativeQuery("select set_config('aegis.current_org', :org, true)")
                .setParameter("org", org.toString()).getSingleResult();
        return org;
    }
}
