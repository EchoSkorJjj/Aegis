package com.aegis.core.ingestion.signal;

import java.time.Instant;
import java.util.UUID;

import com.aegis.core.ingestion.signal.ChangeSignal.SignalType;

public final class ChangeSignalDtos {

    private ChangeSignalDtos() {
    }

    public record ChangeSignalResponse(
            UUID id,
            SignalType signalType,
            String sourceId,
            String externalId,
            String title,
            String summary,
            String severity,
            String url,
            Instant occurredAt,
            Instant createdAt,
            Instant updatedAt) {

        static ChangeSignalResponse from(ChangeSignal signal) {
            return new ChangeSignalResponse(
                    signal.id(), signal.signalType(), signal.sourceId(), signal.externalId(),
                    signal.title(), signal.summary(), signal.severity(), signal.url(),
                    signal.occurredAt(), signal.createdAt(), signal.updatedAt());
        }
    }
}
