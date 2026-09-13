package com.aegis.core.ingestion.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface RawEventRepository extends JpaRepository<RawEventEntity, UUID> {

    boolean existsByDedupKey(String dedupKey);
}
