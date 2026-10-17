package com.aegis.core.ingestion.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.aegis.core.ingestion.RawEvent;
import com.aegis.core.ingestion.RawEventStore;
import com.aegis.core.ingestion.StoredRawEvent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA adapter for the raw-event store. The de-duplication key is {@code sourceId + ":" + externalId}
 * when the feed supplies a (reasonably short) id, otherwise {@code sourceId + ":sha256:" +
 * hash(payload)}. Keys are scoped by {@code sourceId} so two feeds can share an external id. The
 * unique index on {@code dedup_key} is the final guard against a concurrent duplicate insert.
 */
@Repository
class JpaRawEventStore implements RawEventStore {

    /** External ids longer than this are keyed by hash instead, keeping {@code dedup_key} within its column. */
    private static final int MAX_EXTERNAL_ID = 512;

    private final RawEventRepository events;

    JpaRawEventStore(RawEventRepository events) {
        this.events = events;
    }

    @Override
    public boolean saveIfNew(String sourceId, RawEvent event) {
        String dedupKey = dedupKey(sourceId, event);
        if (events.existsByDedupKey(dedupKey)) {
            return false;
        }
        try {
            events.save(new RawEventEntity(
                    UUID.randomUUID(),
                    sourceId,
                    event.externalId(),
                    dedupKey,
                    event.contentType(),
                    event.payload(),
                    event.occurredAt()));
            return true;
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            return false;
        }
    }

    @Override
    public List<StoredRawEvent> findUnprocessed(String sourceId, int limit) {
        return events.findBySourceIdAndProcessedAtIsNullOrderByIngestedAtAsc(sourceId, PageRequest.of(0, limit))
                .stream()
                .map(JpaRawEventStore::toStoredEvent)
                .toList();
    }

    @Override
    @Transactional
    public void markProcessed(UUID id) {
        events.markProcessed(id, Instant.now());
    }

    @Override
    @Transactional
    public void recordFailure(UUID id) {
        events.incrementAttempts(id);
    }

    @Override
    public Optional<Instant> latestOccurredAt(String sourceId) {
        return Optional.ofNullable(events.findLatestOccurredAt(sourceId));
    }

    private static StoredRawEvent toStoredEvent(RawEventEntity entity) {
        return new StoredRawEvent(
                entity.getId(),
                entity.getSourceId(),
                entity.getExternalId(),
                entity.getContentType(),
                entity.getPayload(),
                entity.getOccurredAt(),
                entity.getIngestedAt(),
                entity.getAttempts());
    }

    static String dedupKey(String sourceId, RawEvent event) {
        String externalId = event.externalId() == null ? null : event.externalId().trim();
        if (externalId != null && !externalId.isEmpty() && externalId.length() <= MAX_EXTERNAL_ID) {
            return sourceId + ":" + externalId;
        }
        String basis = externalId == null || externalId.isEmpty() ? event.payload() : externalId;
        return sourceId + ":sha256:" + sha256(basis);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
