package com.aegis.core.ingestion.vendorstatus;

import java.util.ArrayList;
import java.util.List;

import com.aegis.core.ingestion.incident.VendorIncident;
import com.aegis.core.ingestion.vendorstatus.VendorStatusProperties.Vendor;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/**
 * Reads the Atlassian Statuspage JSON API ({@code /api/v2/incidents.json}), which nearly every hosted
 * status page speaks. Richer than RSS: it carries a stable incident id, a lifecycle status, an impact
 * level and a resolution time, all of which survive into the normalised incident.
 */
@Component
class StatuspageFeedReader implements VendorFeedReader {

    private static final int MAX_STATUS = 32;
    private static final int MAX_IMPACT = 32;

    private final ObjectReader reader;

    StatuspageFeedReader(ObjectMapper objectMapper) {
        // Explicitly tolerant of unknown fields: Statuspage publishes far more than this reads.
        this.reader = objectMapper.reader()
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .forType(StatuspageDocument.class);
    }

    @Override
    public List<VendorIncident> read(String payload, Vendor vendor) {
        StatuspageDocument document = reader.readValue(payload);
        if (document == null || document.incidents() == null) {
            return List.of();
        }
        List<VendorIncident> incidents = new ArrayList<>();
        for (Incident incident : document.incidents()) {
            if (incident == null || incident.id() == null || incident.id().isBlank()) {
                continue;
            }
            incidents.add(toIncident(incident, vendor));
            if (incidents.size() >= MAX_INCIDENTS) {
                break;
            }
        }
        return incidents;
    }

    private static VendorIncident toIncident(Incident incident, Vendor vendor) {
        // Statuspage lists updates newest first; the latest one is the current story of the incident.
        String summary = incident.incidentUpdates() == null || incident.incidentUpdates().isEmpty()
                ? null
                : incident.incidentUpdates().get(0).body();
        String startedAt = incident.startedAt() != null ? incident.startedAt() : incident.createdAt();
        return new VendorIncident(
                null, // assigned by the store on first write
                VendorStatusSource.SOURCE_ID,
                VendorFeedReader.externalId(vendor, incident.id().trim()),
                vendor.key(),
                vendor.name(),
                incident.name() == null || incident.name().isBlank() ? "(untitled incident)" : incident.name(),
                truncate(incident.status(), MAX_STATUS),
                truncate(incident.impact(), MAX_IMPACT),
                incident.shortlink(),
                summary,
                FeedTimestamps.parse(startedAt),
                FeedTimestamps.parse(incident.updatedAt()),
                FeedTimestamps.parse(incident.resolvedAt()));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** The slice of the Statuspage schema this reads; unknown fields are ignored. */
    record StatuspageDocument(List<Incident> incidents) {
    }

    record Incident(
            String id,
            String name,
            String status,
            String impact,
            String shortlink,
            @JsonProperty("started_at") String startedAt,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt,
            @JsonProperty("resolved_at") String resolvedAt,
            @JsonProperty("incident_updates") List<IncidentUpdate> incidentUpdates) {
    }

    record IncidentUpdate(String status, String body, @JsonProperty("created_at") String createdAt) {
    }
}
