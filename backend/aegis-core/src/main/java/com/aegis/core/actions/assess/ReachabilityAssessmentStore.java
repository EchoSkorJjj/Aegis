package com.aegis.core.actions.assess;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** The AI's reachability suggestions (V41), scoped to the caller's organization. */
public interface ReachabilityAssessmentStore {

    /** Those for {@code vulnerabilityId} in any of {@code serviceIds}, at whatever commit. */
    List<ReachabilityAssessment> findFor(Collection<UUID> serviceIds, String vulnerabilityId);

    /** Every assessment kept for these services, at whatever commit. */
    List<ReachabilityAssessment> findForServices(Collection<UUID> serviceIds);

    /** Keep {@code assessment}, replacing the one for the same service, package, vulnerability and commit. */
    ReachabilityAssessment save(ReachabilityAssessment assessment);
}
