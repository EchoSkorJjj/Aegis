package com.aegis.core.platform.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only view of identity-service's member directory, <em>as the current caller sees it</em>.
 *
 * <p>aegis-core never trusts a client-supplied user id on its own: before acting on one (e.g. making
 * someone a service's owner) it resolves the id here. Lookups carry the caller's own access token, so
 * identity-service applies the caller's org scope — a user outside the caller's active organization is
 * simply not found, and a cross-tenant reference cannot be made.
 */
public interface IdentityDirectory {

    /**
     * The member with this id in the caller's active organization, or empty when there is no such
     * member there.
     *
     * @throws com.aegis.core.platform.common.ApiExceptions.UpstreamUnavailable identity-service could not
     *     be reached or answered with an error
     */
    Optional<DirectoryUser> findMember(UUID userId);

    /**
     * Every member of the caller's active organization, as the caller's own member directory lists them: for naming
     * many people at once (an evidence export) in a few requests rather than one each.
     *
     * @throws com.aegis.core.platform.common.ApiExceptions.UpstreamUnavailable identity-service could not
     *     be reached or answered with an error
     */
    List<DirectoryUser> members();
}
