package com.usermgmt.identity.auth.email;

import java.util.List;

/**
 * The items a service owner is told about in one email: each needs their decision in Aegis. aegis-core decides
 * who gets which items and never twice; identity-service owns the address and the wording.
 *
 * @param items newest batch for this owner, worst first; never empty
 */
public record OwnerNotification(List<Item> items) {

    public OwnerNotification {
        items = List.copyOf(items);
    }

    /**
     * One item: what changed (a vulnerability or a vendor outage), in which service, and where it is in the app
     * — a path on the frontend, such as {@code /findings/<id>}, never a full URL.
     */
    public record Item(String title, String serviceName, String path) {
    }
}
