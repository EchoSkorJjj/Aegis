package com.aegis.core.actions.upgrade;

import com.aegis.core.actions.pr.PrProviders;
import com.aegis.core.actions.pr.RepositoryRef;
import com.aegis.core.inventory.domain.RepositoryUrl;
import com.aegis.core.inventory.sbom.SbomGenerationProperties;
import com.aegis.core.platform.common.ApiExceptions;
import org.springframework.stereotype.Component;

/**
 * Whether Aegis can open a pull request in a service's repository: a pull-request provider has to serve its host.
 * Only the GitHub App does, so a GitLab or Bitbucket repository imports its SBOM but gets a ticket without a pull
 * request — said up front, on the recommendation and the ticket, rather than failing at approval.
 */
@Component
class PullRequestHosts {

    private final PrProviders providers;
    private final SbomGenerationProperties repositoryHosts;

    PullRequestHosts(PrProviders providers, SbomGenerationProperties repositoryHosts) {
        this.providers = providers;
        this.repositoryHosts = repositoryHosts;
    }

    /** Why no pull request can be opened in the repository at {@code repositoryUrl}, or null when one can. */
    String unsupported(String repositoryUrl) {
        RepositoryUrl url;
        try {
            url = RepositoryUrl.parse(repositoryUrl, repositoryHosts.allowedHosts());
        } catch (ApiExceptions.BadRequest invalid) {
            return "Aegis can't open pull requests in " + repositoryUrl;
        }
        return providers.supports(new RepositoryRef(url.host(), url.path())) ? null
                : "No pull-request provider is enabled for " + url.host() + ", so Aegis can't open the pull request";
    }
}
