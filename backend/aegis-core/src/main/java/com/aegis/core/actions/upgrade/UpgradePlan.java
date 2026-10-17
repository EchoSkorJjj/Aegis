package com.aegis.core.actions.upgrade;

import java.util.List;
import java.util.UUID;

import com.aegis.core.actions.upgrade.UpgradeProposal.Edit;
import com.aegis.core.matching.MatchingDtos.ChangeRef;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;

/**
 * What the upgrade pull request of a response would change: for one vulnerability in one service, every
 * edit Aegis can make in the service's repository — each direct dependency the vulnerability matches, in
 * every manifest that declares it — and, in plain words, what it cannot automate and why. Worked out
 * deterministically ({@link UpgradeProposalService#plan}); nothing is written until it is drafted.
 */
public final class UpgradePlan {

    private final UUID changeSignalId;
    private final UUID serviceId;
    private final ChangeRef change;
    private final String vulnerabilityId;
    private final List<Edit> edits;
    private final List<String> notAutomated;
    private final Facts facts;

    /** What the pull request's text states besides the edits. */
    record Facts(String serviceName, String criticality, String exposure, Double score, String tier,
                 String repositoryUrl, String baseBranch, MatchConfidence confidence, List<String> checked,
                 List<String> notCleared, List<String> evidence, List<String> followUps) {
    }

    UpgradePlan(UUID changeSignalId, UUID serviceId, ChangeRef change, String vulnerabilityId, List<Edit> edits,
            List<String> notAutomated, Facts facts) {
        this.changeSignalId = changeSignalId;
        this.serviceId = serviceId;
        this.change = change;
        this.vulnerabilityId = vulnerabilityId;
        this.edits = List.copyOf(edits);
        this.notAutomated = List.copyOf(notAutomated);
        this.facts = facts;
    }

    /** A change no upgrade fixes, such as a vendor outage. */
    static UpgradePlan none(UUID changeSignalId, UUID serviceId, ChangeRef change) {
        return new UpgradePlan(changeSignalId, serviceId, change, change.externalId(), List.of(), List.of(), null);
    }

    public UUID changeSignalId() {
        return changeSignalId;
    }

    public UUID serviceId() {
        return serviceId;
    }

    /** The edits the pull request would make, in order; empty when there is nothing Aegis can automate. */
    public List<Edit> edits() {
        return edits;
    }

    /** Why each affected dependency it leaves out can't be automated, in words a person can act on. */
    public List<String> notAutomated() {
        return notAutomated;
    }

    public boolean automatable() {
        return !edits.isEmpty();
    }

    ChangeRef change() {
        return change;
    }

    String vulnerabilityId() {
        return vulnerabilityId;
    }

    Facts facts() {
        return facts;
    }
}
