package com.aegis.core.actions.triage;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** The AI's readings of material findings (V46), scoped to the caller's organization. */
public interface FindingInterpretationStore {

    /** Every reading kept for these services. */
    List<FindingInterpretation> findForServices(Collection<UUID> serviceIds);

    /** The readings of this change, in whichever services it reaches. */
    List<FindingInterpretation> findForChange(UUID changeSignalId);

    /** Keep {@code interpretation}, replacing the one for the same service and item. */
    FindingInterpretation save(FindingInterpretation interpretation);
}
