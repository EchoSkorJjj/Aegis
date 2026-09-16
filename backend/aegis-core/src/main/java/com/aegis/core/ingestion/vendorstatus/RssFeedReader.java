package com.aegis.core.ingestion.vendorstatus;

import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import com.aegis.core.ingestion.incident.VendorIncident;
import com.aegis.core.ingestion.vendorstatus.VendorStatusProperties.Vendor;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Reads an RSS 2.0 or Atom status feed — the fallback for vendors that publish no Statuspage JSON
 * (AWS, for one). Feeds carry a title, a link, a timestamp and a body, but no lifecycle status or
 * impact level, so those stay null on the normalised incident rather than being invented here.
 *
 * <p><b>This parses untrusted third-party XML.</b> The parser is therefore hardened against XXE:
 * DOCTYPE declarations are rejected outright, external entities are not resolved, and entity
 * references are not expanded — so a malicious feed cannot read local files or reach internal hosts.
 */
@Component
class RssFeedReader implements VendorFeedReader {

    private static final String DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";
    private static final String LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    /** Keeps a pathological guid out of the external id, which is otherwise unbounded text. */
    private static final int MAX_ID = 256;

    @Override
    public List<VendorIncident> read(String payload, Vendor vendor) {
        Document document = parseSecurely(payload);

        List<Element> entries = elementsNamed(document, "item"); // RSS 2.0
        if (entries.isEmpty()) {
            entries = elementsNamed(document, "entry"); // Atom
        }

        List<VendorIncident> incidents = new ArrayList<>();
        for (Element entry : entries) {
            incidents.add(toIncident(entry, vendor));
            if (incidents.size() >= MAX_INCIDENTS) {
                break;
            }
        }
        return incidents;
    }

    private static VendorIncident toIncident(Element entry, Vendor vendor) {
        String title = firstNonBlank(text(entry, "title"), "(untitled incident)");
        String link = firstNonBlank(text(entry, "link"), attribute(entry, "link", "href"));
        String summary = firstNonBlank(text(entry, "description"), text(entry, "summary"), text(entry, "content"));
        String published = firstNonBlank(
                text(entry, "pubDate"), text(entry, "updated"), text(entry, "published"));

        // A feed without a stable id still needs one that survives re-polls, so fall back to a hash
        // of what identifies the entry rather than inventing a fresh id each poll.
        String id = firstNonBlank(text(entry, "guid"), text(entry, "id"), link);
        if (id == null) {
            id = "sha256:" + Digests.sha256(title + "|" + published);
        }
        if (id.length() > MAX_ID) {
            id = "sha256:" + Digests.sha256(id);
        }

        Instant timestamp = FeedTimestamps.parse(published);
        return new VendorIncident(
                null, // assigned by the store on first write
                VendorStatusSource.SOURCE_ID,
                VendorFeedReader.externalId(vendor, id),
                vendor.key(),
                vendor.name(),
                title,
                null, // RSS carries no lifecycle status
                null, // nor an impact level
                link,
                summary,
                timestamp,
                timestamp,
                null);
    }

    /**
     * A {@link DocumentBuilder} that will not fetch anything the feed points at. {@code
     * disallow-doctype-decl} is the decisive one: with no DOCTYPE there can be no entity declaration,
     * so the classic XXE payload fails at parse time instead of being resolved.
     */
    private static Document parseSecurely(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(DISALLOW_DOCTYPE, true);
            factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
            factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
            factory.setFeature(LOAD_EXTERNAL_DTD, false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            // Belt and braces: even if a DTD slipped through, resolve every entity to nothing.
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception ex) {
            throw new IllegalArgumentException("vendor status feed is not parseable XML: " + ex, ex);
        }
    }

    private static List<Element> elementsNamed(Document document, String tag) {
        NodeList nodes = document.getElementsByTagName(tag);
        List<Element> elements = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    /** The first direct-or-nested child with this tag, trimmed; null when absent or blank. */
    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        Node node = nodes.item(0);
        String value = node.getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String attribute(Element parent, String tag, String attribute) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0 || !(nodes.item(0) instanceof Element element)) {
            return null;
        }
        String value = element.getAttribute(attribute);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
