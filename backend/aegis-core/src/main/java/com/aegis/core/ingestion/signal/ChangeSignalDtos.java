package com.aegis.core.ingestion.signal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aegis.core.ingestion.signal.ChangeSignal.SignalType;

public final class ChangeSignalDtos {

    private ChangeSignalDtos() {
    }

    /** The distinct values available for the source/severity filter selects. */
    public record ChangeSignalFacets(List<String> sources, List<String> severities) {
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
                    signal.title(), signal.summary(), signal.severity(), resolveUrl(signal),
                    signal.occurredAt(), signal.createdAt(), signal.updatedAt());
        }

        /**
         * The link a signal opens to. Vendor incidents and security news carry their own source URL;
         * advisories don't, so fall back to the feed's canonical detail page (e.g. NVD's CVE permalink)
         * derived from the source + external id. Keyed on {@code sourceId} so a new advisory feed can
         * add its own pattern without disturbing the others.
         */
        private static String resolveUrl(ChangeSignal signal) {
            if (signal.url() != null && !signal.url().isBlank()) {
                return signal.url();
            }
            if (signal.externalId() == null || signal.externalId().isBlank()) {
                return null;
            }
            // "nvd" is NvdSource.SOURCE_ID; /vuln/detail/{cve} is NVD's stable public permalink.
            return "nvd".equals(signal.sourceId())
                    ? "https://nvd.nist.gov/vuln/detail/" + signal.externalId()
                    : null;
        }
    }
}
