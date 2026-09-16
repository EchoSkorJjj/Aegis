package com.aegis.core.ingestion.news;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class NewsItemDtos {

    private NewsItemDtos() {
    }

    public record NewsItemResponse(
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

        static NewsItemResponse from(NewsItem item) {
            return new NewsItemResponse(
                    item.id(),
                    item.sourceId(),
                    item.externalId(),
                    item.feedKey(),
                    item.feedName(),
                    item.title(),
                    item.url(),
                    item.summary(),
                    item.publishedAt(),
                    item.cveReferences());
        }
    }
}
