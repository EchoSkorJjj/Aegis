package com.aegis.core.ingestion.vendorstatus;

import java.util.List;

import com.aegis.core.ingestion.incident.VendorIncident;
import com.aegis.core.ingestion.vendorstatus.VendorStatusProperties.Vendor;

/**
 * Turns one captured status document into normalised incidents. One implementation per feed format;
 * the connector captures the document verbatim and the reader interprets it later, so a reader bug
 * never costs data that was already fetched.
 */
interface VendorFeedReader {

    /** Incidents per feed document. Kept well above what any status page publishes at once. */
    int MAX_INCIDENTS = 500;

    /** The external id of a normalised incident: vendor-scoped so two vendors cannot collide. */
    static String externalId(Vendor vendor, String incidentId) {
        return vendor.key() + ":" + incidentId;
    }

    List<VendorIncident> read(String payload, Vendor vendor);
}
