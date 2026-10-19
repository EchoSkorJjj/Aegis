package com.aegis.core.actions.pr;

import java.util.Locale;

import com.aegis.core.platform.common.ApiExceptions;

/** Provider-neutral destination. Authorization and installation binding remain provider concerns. */
public record RepositoryRef(String host, String path) {

    public RepositoryRef {
        if (host == null || !host.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")) {
            throw new ApiExceptions.BadRequest("A valid repository host is required");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (path == null || !path.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}(?:/[A-Za-z0-9][A-Za-z0-9._-]{0,99})+")) {
            throw new ApiExceptions.BadRequest("A repository path must be owner/repository (with optional subgroups)");
        }
    }
}
