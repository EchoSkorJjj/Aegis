package com.aegis.core.ingestion.signal.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ChangeSignalJpaRepository extends JpaRepository<ChangeSignalEntity, UUID> {
}
