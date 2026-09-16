package com.aegis.core.ingestion.news;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Persistence port for normalised security news items. */
public interface NewsItemStore {

    /**
     * Insert the item, or update the existing one with the same {@code (sourceId, externalId)}.
     * CVE references are replaced wholesale — publishers edit articles, and merging reference rows
     * would strand ones the article no longer mentions.
     */
    NewsItem upsert(NewsItem item);

    Optional<NewsItem> findByExternalId(String sourceId, String externalId);

    /** A page of items, most recently published first, each with its CVE references. */
    Page<NewsItem> findPage(Pageable pageable);

    /**
     * The articles mentioning one CVE. This is the join matching will use to carry news onto an
     * advisory already ingested, and through it to the components and services at risk.
     */
    Page<NewsItem> findPageByCveReference(String cveId, Pageable pageable);
}
