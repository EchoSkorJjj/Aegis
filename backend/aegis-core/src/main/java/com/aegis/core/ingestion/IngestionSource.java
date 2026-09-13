package com.aegis.core.ingestion;

import java.util.List;

/**
 * A pollable external feed (advisories, vendor status, threat intel). Each connector implements this
 * as a Spring {@code @Component}; the {@link IngestionScheduler} discovers every such bean and polls
 * it on a schedule. Adding connectors is how later stories (CVE/NVD, vendor status, news) plug in —
 * this interface is the seam, and no connector is provided here.
 *
 * <p>Implementations only fetch and return raw payloads; the framework tags them with
 * {@link #sourceId()}, de-duplicates, and persists. Polls may overlap previous ones — de-duplication
 * makes that safe, so a connector can simply re-fetch a recent window rather than track an exact
 * cursor.
 */
public interface IngestionSource {

    /**
     * Stable, unique identifier for this feed (e.g. {@code "nvd"}, {@code "aws-statuspage"}). Scopes
     * de-duplication and, later, per-feed metrics. Must be non-blank and stable across restarts.
     */
    String sourceId();

    /**
     * Fetch the raw events currently available from the feed. Throwing is acceptable — the framework
     * applies retry/backoff and isolates this source's failure from the others.
     */
    List<RawEvent> poll() throws Exception;
}
