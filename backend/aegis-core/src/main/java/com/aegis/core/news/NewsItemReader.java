package com.aegis.core.news;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aegis.core.ingestion.connectors.feed.FeedEntry;
import com.aegis.core.ingestion.connectors.feed.SyndicationFeedParser;
import com.aegis.core.news.NewsProperties.Feed;
import org.springframework.stereotype.Component;

/**
 * Turns one captured news document into normalised articles, extracting the CVE identifiers each one
 * mentions.
 *
 * <p>The extraction is a plain pattern match over the title and summary — no NLP, no model. That is
 * the point: news is attacker-influenceable prose, so the only thing read out of it is a
 * well-formed identifier that matching can join against advisories already ingested. Everything else
 * stays inert text.
 */
@Component
class NewsItemReader {

    /** {@code CVE-YYYY-NNNN} with at least four digits in the sequence, per the CVE ID syntax. */
    private static final Pattern CVE_ID = Pattern.compile("\\bCVE-\\d{4}-\\d{4,7}\\b", Pattern.CASE_INSENSITIVE);

    /** An article citing more identifiers than this is a listicle; keep the first few. */
    static final int MAX_CVE_REFERENCES = 50;

    private final SyndicationFeedParser parser;

    NewsItemReader(SyndicationFeedParser parser) {
        this.parser = parser;
    }

    List<NewsItem> read(String payload, Feed feed) {
        return parser.parse(payload).stream().map(entry -> toItem(entry, feed)).toList();
    }

    private static NewsItem toItem(FeedEntry entry, Feed feed) {
        String title = entry.title() == null ? "(untitled article)" : entry.title();
        return new NewsItem(
                null, // assigned by the store on first write
                NewsSource.SOURCE_ID,
                feed.key() + ":" + entry.id(),
                feed.key(),
                feed.name(),
                title,
                entry.link(),
                entry.summary(),
                entry.publishedAt(),
                extractCveIds(title, entry.summary()));
    }

    /** Distinct CVE ids across the given texts, upper-cased, in the order they first appear. */
    static List<String> extractCveIds(String... texts) {
        Set<String> ids = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            Matcher matcher = CVE_ID.matcher(text);
            while (matcher.find() && ids.size() < MAX_CVE_REFERENCES) {
                ids.add(matcher.group().toUpperCase(Locale.ROOT));
            }
        }
        return List.copyOf(ids);
    }
}
