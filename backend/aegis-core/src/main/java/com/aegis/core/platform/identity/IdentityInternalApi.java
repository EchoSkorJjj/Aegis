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
     * only to an active member of {@code orgId} whose account can be emailed.
     *
     * @return {@link EmailOutcome#SENT}, or {@link EmailOutcome#UNREACHABLE} when that member can't be emailed (not
     *     an active member, disabled, unverified) — asking again won't change it
     * @throws com.aegis.core.platform.common.ApiExceptions.UpstreamUnavailable when it may work later
     */
    EmailOutcome emailOwner(UUID orgId, UUID userId, List<OwnerEmailItem> items);

    enum EmailOutcome { SENT, UNREACHABLE }

    /** One item: what changed, in which service, and its path in the app (such as {@code /findings/<id>}). */
    record OwnerEmailItem(String title, String serviceName, String path) {
    }
}
