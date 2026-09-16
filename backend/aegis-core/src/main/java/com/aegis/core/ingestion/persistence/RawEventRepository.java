package com.aegis.core.ingestion.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RawEventRepository extends JpaRepository<RawEventEntity, UUID> {

    boolean existsByDedupKey(String dedupKey);

    List<RawEventEntity> findBySourceIdAndProcessedAtIsNullOrderByIngestedAtAsc(String sourceId, Pageable pageable);

    @Query("select max(e.occurredAt) from RawEventEntity e where e.sourceId = :sourceId")
    Instant findLatestOccurredAt(@Param("sourceId") String sourceId);

    /**
     * Updated with a query rather than {@code save()}: {@link RawEventEntity} reports
     * {@code isNew() == true} for every instance, so persisting one would attempt a second insert.
     */
    @Modifying
    @Query("update RawEventEntity e set e.processedAt = :processedAt where e.id = :id and e.processedAt is null")
    int markProcessed(@Param("id") UUID id, @Param("processedAt") Instant processedAt);

    /** Advance the transient-failure counter; updated by query for the same reason as {@link #markProcessed}. */
    @Modifying
    @Query("update RawEventEntity e set e.attempts = e.attempts + 1 where e.id = :id")
    int incrementAttempts(@Param("id") UUID id);
}
