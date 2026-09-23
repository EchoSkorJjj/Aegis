package com.aegis.core.news;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.aegis.core.ingestion.RawEventDrainer;
import com.aegis.core.ingestion.UnparseableEventException;
import com.aegis.core.news.NewsProperties.Feed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains captured news documents into normalised articles.
 *
 * <p>One raw event holds a whole feed, so this writes many records per event. Each is an upsert keyed
 * on {@code (source, feed-key:entry-id)}, which makes re-reading an unchanged article harmless: a feed
 * republishes its recent entries on every poll, and the same row is updated rather than duplicated.
 */
@Component
@ConditionalOnProperty(name = "aegis.ingestion.news.enabled", havingValue = "true")
class NewsNormalizer {

    private static final Logger log = LoggerFactory.getLogger(NewsNormalizer.class);

    private final RawEventDrainer drainer;
    private final NewsItemStore items;
    private final NewsItemReader reader;
    private final Map<String, Feed> feedsByKey;
    private final int batchSize;

    NewsNormalizer(
            RawEventDrainer drainer,
            NewsItemStore items,
            NewsItemReader reader,
            NewsProperties properties) {
        this.drainer = drainer;
        this.items = items;
        this.reader = reader;
        this.batchSize = properties.normalizeBatchSize();
        this.feedsByKey = properties.feeds().stream()
                .collect(Collectors.toMap(Feed::key, Function.identity(), (first, second) -> first));
    }

    @Scheduled(
            fixedDelayString = "${aegis.ingestion.news.normalize-interval:PT1M}",
            initialDelayString = "${aegis.ingestion.news.normalize-interval:PT1M}")
    void normalizePending() {
        drain();
    }

    /**
     * Normalise the pending news documents. Returns how many articles were written — not how many
     * documents were read, which is what the drainer counts. Package-visible so the drain can be
     * tested without waiting on the scheduler.
     */
    int drain() {
        int[] written = {0};
        drainer.drain(NewsSource.SOURCE_ID, batchSize, event -> {
            List<NewsItem> parsed;
            try {
                // Terminal (drop, keeping the raw row): a malformed document, or a feed no longer
                // configured. Only the upsert below is transient and worth retrying.
                Feed feed = feedFor(event.externalId());
                parsed = reader.read(event.payload(), feed);
            } catch (RuntimeException terminal) {
                throw new UnparseableEventException(NewsSource.SOURCE_ID, event.externalId(), terminal);
            }
            parsed.forEach(items::upsert);
            written[0] += parsed.size();
        });
        if (written[0] > 0) {
            log.info("security-news: {} article(s) normalised", written[0]);
        }
        return written[0];
    }

    /**
     * @throws IllegalStateException the event names a feed no longer configured. The drainer treats
     *     that as terminal and drops the event; its raw payload is retained, so re-adding the feed and
     *     clearing {@code processed_at} replays it.
     */
    private Feed feedFor(String externalId) {
        String key = NewsSource.feedKeyOf(externalId);
        Feed feed = feedsByKey.get(key);
        if (feed == null) {
            throw new IllegalStateException("no configured news feed for key '" + key + "'");
        }
        return feed;
    }
}
