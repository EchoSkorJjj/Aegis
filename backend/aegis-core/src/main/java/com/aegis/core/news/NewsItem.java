package com.aegis.core.news;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A normalised security news article — the third kind of "real-world change" ingestion feeds to
 * matching, after advisories and vendor incidents.
 *
 * <p>News is the loosest of the three: an advisory names an affected package and an incident names an
 * impaired vendor, but an article only tells a story. What makes it actionable is
 * {@code cveReferences} — the CVE identifiers found in its title and summary, extracted by plain
 * pattern match, never by an LLM. Matching joins those to advisories already ingested, and through
 * them to the components and services actually at risk. An article mentioning no CVE still lands; it
 * simply carries no join.
 *
 * <p>{@code id} is null on a freshly parsed item — the store assigns it on first write.
 */
public record NewsItem(
        UUID id,
        String sourceId,
        String externalId,
        String feedKey,
        String feedName,
        String title,
        String url,
        String summary,
        Instant publishedAt,
        List<String> cveReferences) {

    public NewsItem {
        cveReferences = cveReferences == null ? List.of() : List.copyOf(cveReferences);
    }
}
