package com.aegis.core.platform.scheduling.persistence;

import java.time.Duration;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.aegis.core.platform.scheduling.SchedulerLeaseStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lease as one row per name, taken and extended by a single upsert so two instances can't both win.
 * Each call commits on its own ({@code REQUIRES_NEW}): a lease held until a caller's transaction ends would
 * hold nothing.
 */
@Repository
class JpaSchedulerLeaseStore implements SchedulerLeaseStore {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean acquireOrRenew(String name, String holder, Duration duration) {
        int taken = entityManager.createNativeQuery("""
                insert into {h-schema}scheduler_leases as lease (name, holder, expires_at)
                values (:name, :holder, clock_timestamp() + :millis * interval '1 millisecond')
                on conflict (name) do update set holder = excluded.holder, expires_at = excluded.expires_at
                where lease.holder = excluded.holder or lease.expires_at < clock_timestamp()
                """)
                .setParameter("name", name)
                .setParameter("holder", holder)
                .setParameter("millis", duration.toMillis())
                .executeUpdate();
        return taken == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String name, String holder) {
        entityManager.createNativeQuery("delete from {h-schema}scheduler_leases where name = :name and holder = :holder")
                .setParameter("name", name)
                .setParameter("holder", holder)
                .executeUpdate();
    }
}
