package com.aegis.core.news;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.aegis.core.ingestion.IngestionSource;
import com.aegis.core.ingestion.RawEvent;
import com.aegis.core.ingestion.connectors.feed.Digests;
import com.aegis.core.ingestion.connectors.feed.FeedHttpClient;
import com.aegis.core.news.NewsProperties.Feed;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Ingestion connector for security news and threat-intel feeds (CISA, vendor-neutral press, research
 * blogs). Each poll captures every configured publisher's RSS or Atom document exactly as returned;
 * turning documents into articles is {@link NewsNormalizer}'s job.
 *
 * <p><b>Whole-document capture.</b> A news feed has no window or cursor API — it simply serves its
 * latest entries. So the connector stores each document as one raw event keyed
 * {@code <feed-key>@<sha256 of the document>}: an unchanged feed hashes to an event already stored and
 * de-duplicates away, while any new article is a new event.
 *
 * <p><b>Per-feed isolation.</b> The framework already isolates one source's failure from another's,
 * but this source speaks to many publishers, so it also catches per feed: one blog being down, slow,
 * or malformed must not cost the others their poll.
 */
@Component
@ConditionalOnProperty(name = "aegis.ingestion.news.enabled", havingValue = "true")
class NewsSource implements IngestionSource {

    static final String SOURCE_ID = "security-news";

    private static final Logger log = LoggerFactory.getLogger(NewsSource.class);

    private final FeedHttpClient client;
    private final NewsProperties properties;

    NewsSource(NewsProperties properties, ObservationRegistry observationRegistry) {
        this.client = new FeedHttpClient(
                properties.connectTimeout(),
                properties.readTimeout(),
                properties.maxResponseBytes(),
                properties.maxRedirects(),
                properties.allowPrivateHosts(),
                observationRegistry);
        this.properties = properties;
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<RawEvent> poll() {
        if (properties.feeds().isEmpty()) {
            log.debug("security-news: no feeds configured");
            return List.of();
        }
        List<RawEvent> events = new ArrayList<>();
        for (Feed feed : properties.feeds()) {
            try {
                capture(feed).ifPresent(events::add);
            } catch (Exception ex) {
                log.warn("security-news: '{}' feed failed ({}): {}", feed.key(), feed.url(), ex.toString());
            }
        }
        log.debug("security-news: captured {} of {} feed(s)", events.size(), properties.feeds().size());
        return events;
    }

    private Optional<RawEvent> capture(Feed feed) {
        String body = client.fetch(feed.url());
        if (body == null || body.isBlank()) {
            log.warn("security-news: '{}' returned an empty document", feed.key());
            return Optional.empty();
        }
        int bytes = body.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > properties.maxPayloadBytes()) {
            log.warn("security-news: skipping '{}' — document {} bytes exceeds the {} byte cap",
                    feed.key(), bytes, properties.maxPayloadBytes());
            return Optional.empty();
        }
        // Content-addressed: an unchanged feed de-duplicates, any new article is a new event.
        return Optional.of(new RawEvent(
                feed.key() + "@" + Digests.sha256(body), "application/rss+xml", body, null));
    }

    /** The feed key a raw event was captured for, taken back off its external id. */
    static String feedKeyOf(String externalId) {
        if (externalId == null) {
            return null;
        }
        int at = externalId.indexOf('@');
        return at < 0 ? externalId : externalId.substring(0, at);
    }
}
