package com.aegis.core.inventory.sbom;

import java.util.ArrayList;
import java.util.List;

import com.aegis.core.inventory.domain.RepositoryUrl;
import com.aegis.core.inventory.sbom.RepositoryReadAccess.ReadGrant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * How Aegis reads a repository (ADR §6c): a token the person pasted, else the first host integration that grants read
 * access (the organization's GitHub App), else anonymously — saying why no integration could, when one covers the host.
 * Shared by an SBOM import and by reading code back for an assessment (E11-5), which never has a pasted token.
 */
@Component
class RepositoryAccess {

    private final List<RepositoryReadAccess> integrations;

    @Autowired
    RepositoryAccess(ObjectProvider<RepositoryReadAccess> integrations) {
        this(integrations.orderedStream().toList());
    }

    RepositoryAccess(List<RepositoryReadAccess> integrations) {
        this.integrations = List.copyOf(integrations);
    }

    CloneAccess access(RepositoryUrl repository, String token, String username) {
        if (token != null && !token.isBlank()) {
            return CloneAccess.token(CloneCredential.forToken(repository.host(), token, username));
        }
        List<String> unavailable = new ArrayList<>();
        for (RepositoryReadAccess integration : integrations) {
            ReadGrant grant = integration.grant(repository);
            if (grant.credential() != null) {
                return CloneAccess.integration(grant.credential(), grant.via());
            }
            if (grant.unavailable() != null) {
                unavailable.add(grant.unavailable());
            }
        }
        return CloneAccess.anonymous(unavailable.isEmpty() ? null : String.join("; ", unavailable));
    }
}
