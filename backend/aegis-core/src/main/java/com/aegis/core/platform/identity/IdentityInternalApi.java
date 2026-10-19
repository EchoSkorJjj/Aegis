package com.aegis.core.platform.identity;

import java.util.List;
import java.util.UUID;

/**
 * identity-service's internal API, for work no user is asking for — the scheduled owner notifications
 * (E9 story 5). Unlike {@link IdentityDirectory}, which relays the caller's own token, this presents the shared
 * service token ({@code aegis.identity.service-token}), so it is used only where there is no caller.
 */
public interface IdentityInternalApi {

    /** Every organization's id. {@code ApiExceptions.UpstreamUnavailable} if identity-service can't say. */
    List<UUID> organizationIds();

    /**
     * Email a service owner the items that need their decision; identity-service writes the email and sends it
     * only to an active member of {@code orgId} whose account can be emailed. {@code deliveryId} names the email:
     * identity-service sends each one once, so asking again with the same id — after a timeout, say, when the
     * first request may have gone through — never sends it twice.
     *
     * @return {@link EmailOutcome#SENT} (now or before), {@link EmailOutcome#UNREACHABLE} when that member can't be
     *     emailed (not an active member, disabled, unverified), or {@link EmailOutcome#REJECTED} when
     *     identity-service refused the request itself — asking again won't change either
     * @throws com.aegis.core.platform.common.ApiExceptions.UpstreamUnavailable when it may work later (whether or
     *     not the email went out: ask again with the same {@code deliveryId})
     */
    EmailOutcome emailOwner(UUID orgId, UUID userId, UUID deliveryId, List<OwnerEmailItem> items);

    enum EmailOutcome { SENT, UNREACHABLE, REJECTED }

    /** One item: what changed, in which service, and its path in the app (such as {@code /findings/<id>}). */
    record OwnerEmailItem(String title, String serviceName, String path) {
    }
}
