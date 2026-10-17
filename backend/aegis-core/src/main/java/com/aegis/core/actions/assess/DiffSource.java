package com.aegis.core.actions.assess;

import java.net.URI;
import java.util.Optional;

/** Where a fix commit's unified diff comes from (E11-5): its host, over HTTPS; faked in tests. */
interface DiffSource {

    /** The diff at {@code uri}, or empty when the host doesn't serve it now. Never throws. */
    Optional<String> diff(URI uri);
}
