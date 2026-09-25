package com.aegis.core.actions.pr.github;

import java.util.Set;

import com.aegis.core.actions.pr.github.GitHubInstallationTokens.Access;
import com.aegis.core.inventory.domain.RepositoryUrl;
import com.aegis.core.inventory.sbom.CloneCredential;
import com.aegis.core.inventory.sbom.RepositoryReadAccess;
import com.aegis.core.platform.common.ApiExceptions;

/**
 * The inventory's read port for GitHub: clones a repository the App is installed on with a short-lived
 * installation token that can only read that one repository's contents — so importing an SBOM from it needs
 * no personal token. Whether the App may act for the caller's organization is {@link GitHubAccounts}' call.
 */
final class GitHubRepositoryReadAccess implements RepositoryReadAccess {

    private static final String VIA = "the Aegis GitHub App";

    private final GitHubInstallationTokens tokens;
    private final Set<String> hosts;

    GitHubRepositoryReadAccess(GitHubInstallationTokens tokens, Set<String> hosts) {
        this.tokens = tokens;
        this.hosts = hosts;
    }

    @Override
    public ReadGrant grant(RepositoryUrl repository) {
        if (!hosts.contains(repository.host()) || repository.path().split("/").length != 2) {
            return ReadGrant.notCovered();
        }
        String owner = repository.path().split("/")[0];
        try {
            // The username is a placeholder GitHub ignores; the installation token is what authenticates.
            return ReadGrant.granted(
                    new CloneCredential("x-access-token", tokens.forRepository(repository.path(), Access.READ)), VIA);
        } catch (ApiExceptions.NotFound notInstalled) {
            return ReadGrant.unavailable(VIA + " is not installed on " + owner + " or can't reach this repository");
        } catch (ApiExceptions.Forbidden notAllowed) {
            return ReadGrant.unavailable(VIA + " is not enabled for " + owner + " in this organization");
        } catch (ApiExceptions.UpstreamUnavailable unreachable) {
            return ReadGrant.unavailable("GitHub could not be reached to use " + VIA);
        }
    }
}
