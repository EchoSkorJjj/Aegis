package com.aegis.core.ingestion.connectors.feed;

import java.time.Instant;

/**
 * One entry from a syndication feed, reduced to the fields every consumer needs. Format-neutral: an
 * RSS {@code <item>} and an Atom {@code <entry>} both arrive here looking the same.
 *
 * <p>{@code id} is never null — where a feed supplies no {@code guid} or Atom {@code id} the parser
 * falls back to the link, then to a hash of the entry, so the id is stable across re-polls and
 * de-duplication keeps working.
 */
public record FeedEntry(String id, String title, String link, String summary, Instant publishedAt) {
}
