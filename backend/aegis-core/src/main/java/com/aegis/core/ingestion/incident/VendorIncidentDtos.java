package com.aegis.core.ingestion.incident;

import java.time.Instant;
import java.util.UUID;

public final class VendorIncidentDtos {

    private VendorIncidentDtos() {
    }

    public record VendorIncidentResponse(
            UUID id,
            String sourceId,
            String externalId,
            String vendorKey,
            String vendorName,
            String title,
            String status,
            String impact,
            String url,
            String summary,
            Instant startedAt,
            Instant lastUpdateAt,
            Instant resolvedAt,
            boolean resolved) {

        static VendorIncidentResponse from(VendorIncident incident) {
            return new VendorIncidentResponse(
                    incident.id(),
                    incident.sourceId(),
                    incident.externalId(),
                    incident.vendorKey(),
                    incident.vendorName(),
                    incident.title(),
                    incident.status(),
                    incident.impact(),
                    incident.url(),
                    incident.summary(),
                    incident.startedAt(),
                    incident.lastUpdateAt(),
                    incident.resolvedAt(),
                    incident.resolved());
        }
    }
}
