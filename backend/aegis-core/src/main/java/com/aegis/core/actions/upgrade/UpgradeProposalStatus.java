package com.aegis.core.actions.upgrade;

/**
 * Where a safe-upgrade proposal is in the decision workflow (an early slice of E9's state machine).
 * Nothing leaves {@code PROPOSED} without a person: only an approval opens a pull request.
 */
public enum UpgradeProposalStatus {
    /** Drafted and waiting for a decision. */
    PROPOSED,
    /** Approved; the draft pull request is being opened. */
    OPENING,
    /** The draft pull request is open. Merging it is the repository maintainers' decision, never Aegis's. */
    OPENED,
    /** Opening failed. The reason is kept; the proposal can be approved again or dismissed. */
    FAILED,
    /** Declined by a person, with a reason. */
    DISMISSED
}
