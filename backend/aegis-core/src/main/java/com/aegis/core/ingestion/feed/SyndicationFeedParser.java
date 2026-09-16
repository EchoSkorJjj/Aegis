package com.aegis.core.ingestion.feed;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Parses an RSS 2.0 or Atom document into format-neutral {@link FeedEntry} records. Shared by every
 * connector that consumes syndication feeds — vendor status pages and security news alike — so the
 * hardening below exists in exactly one place.
 *
 * <p><b>This parses untrusted third-party XML.</b> The parser is hardened against XXE: DOCTYPE
 * declarations are rejected outright, external entities are not resolved, and entity references are
 * not expanded — so a hostile feed cannot read local files, reach internal hosts, or exhaust memory
 * through entity expansion.
 */
@Component
public class SyndicationFeedParser {

    private static final String DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";
    private static final String LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    /** Entries per document. Kept well above what any real feed publishes at once. */
    public static final int MAX_ENTRIES = 500;

    /** Keeps a pathological guid out of ids that end up in an external id. */
    private static final int MAX_ID = 256;

    /**
     * @throws IllegalArgumentException the document is not well-formed XML, or declares a DOCTYPE
     */
    public List<FeedEntry> parse(String xml) {
        Document document = parseSecurely(xml);

        List<Element> nodes = elementsNamed(document, "item"); // RSS 2.0
        if (nodes.isEmpty()) {
            nodes = elementsNamed(document, "entry"); // Atom
        }

        List<FeedEntry> entries = new ArrayList<>();
        for (Element node : nodes) {
            entries.add(toEntry(node));
            if (entries.size() >= MAX_ENTRIES) {
                break;
            }
        }
        return entries;
    }

    private static FeedEntry toEntry(Element node) {
        String title = firstNonBlank(text(node, "title"));
        String link = firstNonBlank(text(node, "link"), attribute(node, "link", "href"));
        String summary = firstNonBlank(
                text(node, "description"), text(node, "summary"), text(node, "content"), text(node, "encoded"));
        String published = firstNonBlank(
                text(node, "pubDate"), text(node, "updated"), text(node, "published"));

        // A feed without a stable id still needs one that survives re-polls, so fall back to a hash
        // of what identifies the entry rather than inventing a fresh id each poll.
        String id = firstNonBlank(text(node, "guid"), text(node, "id"), link);
        if (id == null) {
            id = "sha256:" + Digests.sha256(title + "|" + published);
        }
        if (id.length() > MAX_ID) {
            id = "sha256:" + Digests.sha256(id);
        }

        return new FeedEntry(id, title, link, stripHtml(summary), FeedTimestamps.parse(published));
    }

    /**
     * A {@link DocumentBuilder} that will not fetch anything the feed points at. {@code
     * disallow-doctype-decl} is the decisive one: with no DOCTYPE there can be no entity declaration,
     * so the classic XXE payload fails at parse time instead of being resolved.
     */
    private static Document parseSecurely(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Namespace-aware so elements are matched by local name — Atom (default namespace) and
            // namespaced extensions like content:encoded resolve, not just unprefixed RSS 2.0.
            factory.setNamespaceAware(true);
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
            throw new IllegalArgumentException("feed is not parseable XML: " + ex, ex);
        }
    }

    /**
     * News feeds put escaped HTML in {@code <description>}. Readers want prose, and the untouched
     * original is always in the raw-event store, so tags and entities are flattened here.
     */
    private static String stripHtml(String value) {
        if (value == null) {
            return null;
        }
        String text = value.replaceAll("<[^>]*>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
        return text.isEmpty() ? null : text;
    }

    private static List<Element> elementsNamed(Document document, String tag) {
        NodeList nodes = document.getElementsByTagNameNS("*", tag);
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
        NodeList nodes = parent.getElementsByTagNameNS("*", tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        Node node = nodes.item(0);
        String value = node.getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String attribute(Element parent, String tag, String attribute) {
        NodeList nodes = parent.getElementsByTagNameNS("*", tag);
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
