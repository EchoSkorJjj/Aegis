package com.aegis.core.ingestion.vendorstatus;

import java.util.List;

import com.aegis.core.ingestion.feed.FeedEntry;
import com.aegis.core.ingestion.feed.SyndicationFeedParser;
import com.aegis.core.ingestion.incident.VendorIncident;
import com.aegis.core.ingestion.vendorstatus.VendorStatusProperties.Vendor;
import org.springframework.stereotype.Component;

/**
 * Reads an RSS 2.0 or Atom status feed — the fallback for vendors that publish no Statuspage JSON
 * (AWS, for one). Feeds carry a title, a link, a timestamp and a body, but no lifecycle status or
 * impact level, so those stay null on the normalised incident rather than being invented here.
 *
 * <p>The XML itself — including the hardening that keeps a hostile feed from reading local files — is
 * {@link SyndicationFeedParser}'s job; this only maps entries onto incidents.
 */
@Component
class RssFeedReader implements VendorFeedReader {

    private final SyndicationFeedParser parser;

    RssFeedReader(SyndicationFeedParser parser) {
        this.parser = parser;
    }

    @Override
    public List<VendorIncident> read(String payload, Vendor vendor) {
        return parser.parse(payload).stream().map(entry -> toIncident(entry, vendor)).toList();
    }

    private static VendorIncident toIncident(FeedEntry entry, Vendor vendor) {
        return new VendorIncident(
                null, // assigned by the store on first write
                VendorStatusSource.SOURCE_ID,
                VendorFeedReader.externalId(vendor, entry.id()),
                vendor.key(),
                vendor.name(),
                entry.title() == null ? "(untitled incident)" : entry.title(),
                null, // RSS carries no lifecycle status
                null, // nor an impact level
                entry.link(),
                entry.summary(),
                entry.publishedAt(),
                entry.publishedAt(),
                null);
    }
}
