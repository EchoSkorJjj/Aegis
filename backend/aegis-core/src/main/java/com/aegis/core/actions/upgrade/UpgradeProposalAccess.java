package com.aegis.core.actions.upgrade;

import java.util.UUID;

import com.aegis.core.inventory.ServiceOwnership;
import com.aegis.core.platform.common.ApiExceptions;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * The method-security check behind {@link UpgradeRoles#DECIDE}: is the caller an assigned owner of the
 * service a proposal belongs to? Org-scoped like every proposal read, so a proposal in another
 * organization is never "owned"; anything that cannot be resolved denies.
 */
@Component("upgradeProposalAccess")
public class UpgradeProposalAccess {

    private final UpgradeProposalStore proposals;
    private final ServiceOwnership ownership;

    public UpgradeProposalAccess(UpgradeProposalStore proposals, ServiceOwnership ownership) {
        this.proposals = proposals;
        this.ownership = ownership;
    }

    public boolean isOwnerOfProposalService(UUID proposalId, Authentication authentication) {
        if (proposalId == null) {
            return false;
        }
        try {
            return proposals.find(proposalId)
                    .map(proposal -> ownership.isAssignedOwner(proposal.serviceId(), authentication))
                    .orElse(false);
        } catch (ApiExceptions.Forbidden noOrganizationContext) {
            return false;
        }
    }
}
