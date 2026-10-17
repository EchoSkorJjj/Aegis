package com.aegis.core.actions.pr;

import java.util.List;

import com.aegis.core.platform.common.ApiExceptions;
import org.springframework.stereotype.Component;

/** Discovers optional provider beans. With no adapter enabled, every destination is refused. */
@Component
public class PrProviders {

    private final List<PrProvider> providers;

    public PrProviders(List<PrProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    /**
     * Whether a pull request can be opened in the repository — exactly one enabled adapter serves its host — so
     * a caller can say so up front instead of failing at approval ({@link #forRepository}).
     */
    public boolean supports(RepositoryRef repository) {
        return repository != null && providers.stream().filter(provider -> provider.supports(repository)).count() == 1;
    }

    public PrProvider forRepository(RepositoryRef repository) {
        if (repository == null) {
            throw new ApiExceptions.BadRequest("A repository is required");
        }
        List<PrProvider> supported = providers.stream().filter(provider -> provider.supports(repository)).toList();
        if (supported.isEmpty()) {
            throw new ApiExceptions.Conflict("No PR provider is enabled for host " + repository.host());
        }
        if (supported.size() > 1) {
            throw new ApiExceptions.Conflict("Multiple PR providers support host " + repository.host());
        }
        return supported.getFirst();
    }
}
