package com.aegis.core.findings;

import java.util.List;

import com.aegis.core.matching.MatchingDtos.ChangeRef;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.matching.MatchingDtos.OwnerRef;
import com.aegis.core.matching.MatchingDtos.ServiceRef;
import com.aegis.core.scoring.ScoringDtos.ImpactTier;
import org.springframework.data.domain.Page;

public final class FindingDtos {
    private FindingDtos() { }

    /** One change, ranked by its worst material service within the selected service/owner scope. */
    public record Item(ChangeRef change, double score, ImpactTier tier, List<ServiceImpact> services) { }

    public record ServiceImpact(ServiceRef service, double score, ImpactTier tier,
            MatchConfidence confidence,
            List<OwnerRef> owners, String explanation) { }

    /** Facets include the whole org estate, even when filters or materiality leave no queued items. */
    public record Queue(Page<Item> items, List<ServiceRef> services, List<OwnerRef> owners,
            int ownedServiceCount) { }
}
