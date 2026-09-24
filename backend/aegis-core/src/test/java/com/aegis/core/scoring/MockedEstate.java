package com.aegis.core.scoring;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.ServiceExposure;
import com.aegis.core.matching.MatchingDtos.ChangeMatchResponse;
import com.aegis.core.matching.MatchingDtos.ChangeRef;
import com.aegis.core.matching.MatchingDtos.ImpactedServiceMatch;
import com.aegis.core.matching.MatchingDtos.MatchConfidence;
import com.aegis.core.matching.MatchingDtos.OwnerRef;
import com.aegis.core.matching.MatchingDtos.ServiceRef;
import com.aegis.core.matching.MatchingService;
import com.aegis.core.signals.ChangeSignal;
import com.aegis.core.signals.ChangeSignal.SignalType;
import com.aegis.core.signals.ChangeSignalStore;
import org.springframework.data.domain.PageImpl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A mocked estate for the scoring property tests: change signals, the advisories behind them, and
 * which services matching says each one reaches. Scoring is exercised for real on top of it — only
 * its three collaborators are stand-ins — so a test can build any estate it needs, shuffle it, and
 * score it again.
 */
final class MockedEstate {

    final MatchingService matching = mock(MatchingService.class);
    final ChangeSignalStore signals = mock(ChangeSignalStore.class);
    final AdvisoryStore advisories = mock(AdvisoryStore.class);

    ScoringService scoring(ImpactScorer scorer) {
        return new ScoringService(matching, signals, advisories, scorer);
    }

    /** A vulnerability signal backed by one NVD advisory with the given CVSS 3.1 base score. */
    ChangeSignal vulnerability(String cve, String cvss, Instant occurredAt) {
        ChangeSignal signal = new ChangeSignal(UUID.randomUUID(), SignalType.VULNERABILITY, "nvd", cve, cve,
                "test", null, null, occurredAt, occurredAt, occurredAt);
        feeds(signal, advisory("nvd", cve, cve, "3.1", cvss));
        return signal;
    }

    /**
     * Stub the advisories every feed holds for the signal's vulnerability, in the order the database
     * happens to return them — the first is the one the signal itself projects.
     */
    void feeds(ChangeSignal signal, Advisory... byFeed) {
        when(signals.findById(signal.id())).thenReturn(Optional.of(signal));
        when(advisories.findByExternalId(signal.sourceId(), signal.externalId())).thenReturn(Optional.of(byFeed[0]));
        when(advisories.findByCanonicalId(signal.externalId())).thenReturn(List.of(byFeed));
    }

    /** A vendor outage signal carrying the vendor's own impact word. */
    ChangeSignal outage(String key, String impact, Instant occurredAt) {
        ChangeSignal signal = new ChangeSignal(UUID.randomUUID(), SignalType.VENDOR_OUTAGE, "vendor-status", key,
                key + " degraded", null, impact, null, occurredAt, occurredAt, occurredAt);
        when(signals.findById(signal.id())).thenReturn(Optional.of(signal));
        return signal;
    }

    /** What matching says the signal reaches, in the order given. */
    void reaches(ChangeSignal signal, List<ImpactedServiceMatch> impacted) {
        when(matching.matchChange(signal.id())).thenReturn(new ChangeMatchResponse(
                new ChangeRef(signal.id(), signal.signalType(), signal.sourceId(), signal.externalId(), signal.title()),
                impacted.size(), 0, impacted));
    }

    void reaches(ChangeSignal signal, ImpactedServiceMatch... impacted) {
        reaches(signal, Arrays.asList(impacted));
    }

    /** The roll-up window's signals, as the store returns them. */
    void window(ChangeSignal... signalsAsStored) {
        when(signals.findPage(any(), any())).thenReturn(new PageImpl<>(List.of(signalsAsStored)));
    }

    static Advisory advisory(String sourceId, String externalId, String canonicalId, String cvssVersion, String cvss) {
        return new Advisory(UUID.randomUUID(), sourceId, externalId, canonicalId, "test", Instant.EPOCH, Instant.EPOCH,
                cvss == null ? null : new Advisory.Cvss(cvssVersion, null, new BigDecimal(cvss), null), List.of());
    }

    static ServiceRef service(String name, BusinessCriticality criticality, ServiceExposure exposure) {
        return service(UUID.randomUUID(), name, criticality, exposure);
    }

    static ServiceRef service(UUID id, String name, BusinessCriticality criticality, ServiceExposure exposure) {
        return new ServiceRef(id, name, criticality, exposure);
    }

    static OwnerRef owner(String name) {
        return owner(UUID.randomUUID(), name);
    }

    static OwnerRef owner(UUID id, String name) {
        return new OwnerRef(id, UUID.randomUUID(), name);
    }

    static ImpactedServiceMatch impacted(ServiceRef service, OwnerRef... owners) {
        return impacted(service, MatchConfidence.CONFIRMED, owners);
    }

    static ImpactedServiceMatch impacted(ServiceRef service, MatchConfidence confidence, OwnerRef... owners) {
        return new ImpactedServiceMatch(service, confidence, List.of(owners), List.of());
    }
}
