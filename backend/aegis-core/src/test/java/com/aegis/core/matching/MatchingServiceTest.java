package com.aegis.core.matching;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.aegis.core.advisories.Advisory;
import com.aegis.core.advisories.AdvisoryStore;
import com.aegis.core.advisories.AffectedProduct;
import com.aegis.core.incidents.VendorIncident;
import com.aegis.core.incidents.VendorIncidentStore;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.InventoryStore.ComponentDependencyLink;
import com.aegis.core.inventory.InventoryStore.ServiceComponentLink;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import com.aegis.core.matching.MatchingDtos.EvidenceKind;
import com.aegis.core.platform.common.ApiExceptions;
import com.aegis.core.signals.ChangeSignal;
import com.aegis.core.signals.ChangeSignal.SignalType;
import com.aegis.core.signals.ChangeSignalStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchingServiceTest {

    @Mock
    private ChangeSignalStore signals;

    @Mock
    private AdvisoryStore advisories;

    @Mock
    private VendorIncidentStore incidents;

    @Mock
    private InventoryStore inventory;

    private MatchingService matching;

    @BeforeEach
    void setUp() {
        matching = new MatchingService(
                signals, advisories, incidents, inventory, new AdvisoryComponentMatcher());
    }

    @Test
    void traversesFromMatchedComponentThroughDependenciesToServiceAndOwner() {
        UUID signalId = UUID.randomUUID();
        ChangeSignal signal = signal(signalId, SignalType.VULNERABILITY, "nvd", "CVE-2099-1000");
        Advisory advisory = new Advisory(
                signalId, "nvd", signal.externalId(), "test", Instant.EPOCH, Instant.EPOCH, null,
                List.of(new AffectedProduct(
                        "cpe:2.3:a:apache:log4j:*:*:*:*:*:*:*:*",
                        "a", "apache", "log4j", null, null, null, null, "2.15.0")));
        SoftwareComponent vulnerable = component("org.apache.logging.log4j:log4j-core", "2.14.1");
        SoftwareComponent direct = component("com.acme:orders", "1.0.0");
        BusinessService service = new BusinessService(UUID.randomUUID(), "Orders", BusinessCriticality.HIGH);
        Owner owner = new Owner(UUID.randomUUID(), "Orders team");

        when(signals.findById(signalId)).thenReturn(Optional.of(signal));
        when(advisories.findByExternalId("nvd", signal.externalId())).thenReturn(Optional.of(advisory));
        when(advisories.findByCanonicalId(signal.externalId())).thenReturn(List.of(advisory));
        when(inventory.findSoftwareComponentsForMatching()).thenReturn(List.of(vulnerable, direct));
        when(inventory.findComponentDependencyLinks(Set.of(vulnerable.getId())))
                .thenReturn(List.of(new ComponentDependencyLink(direct.getId(), vulnerable.getId())));
        when(inventory.findComponentDependencyLinks(Set.of(direct.getId()))).thenReturn(List.of());
        when(inventory.findServiceComponentLinks(Set.of(vulnerable.getId(), direct.getId())))
                .thenReturn(List.of(new ServiceComponentLink(service.getId(), direct.getId())));
        when(inventory.findBusinessServicesByIds(Set.of(service.getId())))
                .thenReturn(Map.of(service.getId(), service));
        when(inventory.findOwnersForServices(Set.of(service.getId())))
                .thenReturn(Map.of(service.getId(), List.of(owner)));
        when(inventory.findSoftwareComponentsByIds(Set.of(vulnerable.getId(), direct.getId())))
                .thenReturn(Map.of(vulnerable.getId(), vulnerable, direct.getId(), direct));

        var response = matching.matchChange(signalId);

        assertThat(response.matchedComponentCount()).isEqualTo(1);
        assertThat(response.impactedServices()).singleElement().satisfies(impact -> {
            assertThat(impact.service().id()).isEqualTo(service.getId());
            assertThat(impact.owners()).extracting(MatchingDtos.OwnerRef::id).containsExactly(owner.getId());
            assertThat(impact.evidence()).singleElement().satisfies(evidence -> {
                assertThat(evidence.kind()).isEqualTo(EvidenceKind.ADVISORY_COMPONENT);
                assertThat(evidence.componentPath())
                        .extracting(MatchingDtos.ComponentRef::id)
                        .containsExactly(vulnerable.getId(), direct.getId());
            });
        });
    }

    @Test
    void traversesVendorDependencyToServiceAndOwner() {
        UUID signalId = UUID.randomUUID();
        ChangeSignal signal = signal(signalId, SignalType.VENDOR_OUTAGE, "vendor-status", "acme:outage-1");
        VendorIncident incident = new VendorIncident(
                signalId, signal.sourceId(), signal.externalId(), "acme", "Acme Cloud", "Outage",
                "investigating", "major", null, null, Instant.EPOCH, null, null);
        ThirdPartyVendor vendor = new ThirdPartyVendor(UUID.randomUUID(), "Acme Cloud");
        BusinessService service = new BusinessService(UUID.randomUUID(), "Checkout", BusinessCriticality.CRITICAL);
        Owner owner = new Owner(UUID.randomUUID(), "Checkout team");

        when(signals.findById(signalId)).thenReturn(Optional.of(signal));
        when(incidents.findByExternalId(signal.sourceId(), signal.externalId())).thenReturn(Optional.of(incident));
        when(inventory.findThirdPartyVendorsForMatching()).thenReturn(List.of(vendor));
        when(inventory.findServiceIdsForVendor(vendor.getId())).thenReturn(List.of(service.getId()));
        when(inventory.findBusinessServicesByIds(List.of(service.getId())))
                .thenReturn(Map.of(service.getId(), service));
        when(inventory.findOwnersForServices(List.of(service.getId())))
                .thenReturn(Map.of(service.getId(), List.of(owner)));

        var response = matching.matchChange(signalId);

        assertThat(response.matchedVendorCount()).isEqualTo(1);
        assertThat(response.impactedServices()).singleElement().satisfies(impact -> {
            assertThat(impact.service().id()).isEqualTo(service.getId());
            assertThat(impact.evidence()).singleElement().satisfies(evidence -> {
                assertThat(evidence.kind()).isEqualTo(EvidenceKind.VENDOR_DEPENDENCY);
                assertThat(evidence.vendor().id()).isEqualTo(vendor.getId());
            });
        });
    }

    @Test
    void rejectsSecurityNewsWithoutInventingPackageOrVendorIdentity() {
        UUID signalId = UUID.randomUUID();
        when(signals.findById(signalId))
                .thenReturn(Optional.of(signal(signalId, SignalType.SECURITY_NEWS, "security-news", "article-1")));

        assertThatThrownBy(() -> matching.matchChange(signalId))
                .isInstanceOf(ApiExceptions.BadRequest.class)
                .hasMessageContaining("no package or vendor identity");
    }

    @Test
    void terminatesOnADependencyCycleAndStillReachesTheService() {
        UUID signalId = UUID.randomUUID();
        ChangeSignal signal = signal(signalId, SignalType.VULNERABILITY, "nvd", "CVE-2099-2000");
        Advisory advisory = new Advisory(
                signalId, "nvd", signal.externalId(), "test", Instant.EPOCH, Instant.EPOCH, null,
                List.of(new AffectedProduct(
                        "cpe:2.3:a:acme:alib:*:*:*:*:*:*:*:*", "a", "acme", "alib",
                        null, null, null, null, null))); // affects all versions
        SoftwareComponent a = component("com.acme:alib", "1.0.0");
        SoftwareComponent b = component("com.acme:blib", "1.0.0");
        BusinessService service = new BusinessService(UUID.randomUUID(), "Svc", BusinessCriticality.LOW);

        when(signals.findById(signalId)).thenReturn(Optional.of(signal));
        when(advisories.findByExternalId("nvd", signal.externalId())).thenReturn(Optional.of(advisory));
        when(advisories.findByCanonicalId(signal.externalId())).thenReturn(List.of(advisory));
        when(inventory.findSoftwareComponentsForMatching()).thenReturn(List.of(a, b));
        // Cycle: b depends on a, and a depends on b.
        when(inventory.findComponentDependencyLinks(Set.of(a.getId())))
                .thenReturn(List.of(new ComponentDependencyLink(b.getId(), a.getId())));
        when(inventory.findComponentDependencyLinks(Set.of(b.getId())))
                .thenReturn(List.of(new ComponentDependencyLink(a.getId(), b.getId())));
        when(inventory.findServiceComponentLinks(Set.of(a.getId(), b.getId())))
                .thenReturn(List.of(new ServiceComponentLink(service.getId(), b.getId())));
        when(inventory.findBusinessServicesByIds(Set.of(service.getId())))
                .thenReturn(Map.of(service.getId(), service));
        when(inventory.findOwnersForServices(Set.of(service.getId()))).thenReturn(Map.of());
        when(inventory.findSoftwareComponentsByIds(Set.of(a.getId(), b.getId())))
                .thenReturn(Map.of(a.getId(), a, b.getId(), b));

        var response = matching.matchChange(signalId);

        assertThat(response.matchedComponentCount()).isEqualTo(1);
        assertThat(response.impactedServices()).singleElement().satisfies(impact ->
                assertThat(impact.evidence()).singleElement().satisfies(evidence ->
                        assertThat(evidence.componentPath()).extracting(MatchingDtos.ComponentRef::id)
                                .containsExactly(a.getId(), b.getId())));
    }

    @Test
    void choosesTheShortestReverseDependencyPathThroughADiamond() {
        UUID signalId = UUID.randomUUID();
        ChangeSignal signal = signal(signalId, SignalType.VULNERABILITY, "nvd", "CVE-2099-3000");
        Advisory advisory = new Advisory(
                signalId, "nvd", signal.externalId(), "test", Instant.EPOCH, Instant.EPOCH, null,
                List.of(new AffectedProduct(
                        "cpe:2.3:a:acme:alib:*:*:*:*:*:*:*:*", "a", "acme", "alib",
                        null, null, null, null, null)));
        SoftwareComponent a = component("com.acme:alib", "1.0.0"); // vulnerable root
        SoftwareComponent b = component("com.acme:blib", "1.0.0");
        SoftwareComponent c = component("com.acme:clib", "1.0.0");
        SoftwareComponent d = component("com.acme:dlib", "1.0.0"); // reachable via A->D (short) or A->B->C->D
        BusinessService service = new BusinessService(UUID.randomUUID(), "Svc", BusinessCriticality.LOW);

        when(signals.findById(signalId)).thenReturn(Optional.of(signal));
        when(advisories.findByExternalId("nvd", signal.externalId())).thenReturn(Optional.of(advisory));
        when(advisories.findByCanonicalId(signal.externalId())).thenReturn(List.of(advisory));
        when(inventory.findSoftwareComponentsForMatching()).thenReturn(List.of(a, b, c, d));
        when(inventory.findComponentDependencyLinks(Set.of(a.getId()))).thenReturn(List.of(
                new ComponentDependencyLink(b.getId(), a.getId()),
                new ComponentDependencyLink(d.getId(), a.getId())));
        when(inventory.findComponentDependencyLinks(Set.of(b.getId(), d.getId())))
                .thenReturn(List.of(new ComponentDependencyLink(c.getId(), b.getId())));
        when(inventory.findComponentDependencyLinks(Set.of(c.getId())))
                .thenReturn(List.of(new ComponentDependencyLink(d.getId(), c.getId())));
        when(inventory.findServiceComponentLinks(Set.of(a.getId(), b.getId(), c.getId(), d.getId())))
                .thenReturn(List.of(new ServiceComponentLink(service.getId(), d.getId())));
        when(inventory.findBusinessServicesByIds(Set.of(service.getId())))
                .thenReturn(Map.of(service.getId(), service));
        when(inventory.findOwnersForServices(Set.of(service.getId()))).thenReturn(Map.of());
        when(inventory.findSoftwareComponentsByIds(Set.of(a.getId(), b.getId(), c.getId(), d.getId())))
                .thenReturn(Map.of(a.getId(), a, b.getId(), b, c.getId(), c, d.getId(), d));

        var response = matching.matchChange(signalId);

        assertThat(response.impactedServices()).singleElement().satisfies(impact ->
                assertThat(impact.evidence()).singleElement().satisfies(evidence ->
                        assertThat(evidence.componentPath()).extracting(MatchingDtos.ComponentRef::id)
                                .containsExactly(a.getId(), d.getId()))); // shortest, not A->B->C->D
    }

    @Test
    void surfacesAnUnknownVersionMatchAsAPossibleServiceImpact() {
        UUID signalId = UUID.randomUUID();
        ChangeSignal signal = signal(signalId, SignalType.VULNERABILITY, "nvd", "CVE-2099-4000");
        Advisory advisory = new Advisory(
                signalId, "nvd", signal.externalId(), "test", Instant.EPOCH, Instant.EPOCH, null,
                List.of(new AffectedProduct(
                        "cpe:2.3:a:acme:alib:*:*:*:*:*:*:*:*", "a", "acme", "alib",
                        null, null, null, null, "2.15.0")));
        SoftwareComponent vulnerable = component("com.acme:alib", "2.14.1.RELEASE"); // non-SemVer
        BusinessService service = new BusinessService(UUID.randomUUID(), "Svc", BusinessCriticality.LOW);

        when(signals.findById(signalId)).thenReturn(Optional.of(signal));
        when(advisories.findByExternalId("nvd", signal.externalId())).thenReturn(Optional.of(advisory));
        when(advisories.findByCanonicalId(signal.externalId())).thenReturn(List.of(advisory));
        when(inventory.findSoftwareComponentsForMatching()).thenReturn(List.of(vulnerable));
        when(inventory.findComponentDependencyLinks(Set.of(vulnerable.getId()))).thenReturn(List.of());
        when(inventory.findServiceComponentLinks(Set.of(vulnerable.getId())))
                .thenReturn(List.of(new ServiceComponentLink(service.getId(), vulnerable.getId())));
        when(inventory.findBusinessServicesByIds(Set.of(service.getId())))
                .thenReturn(Map.of(service.getId(), service));
        when(inventory.findOwnersForServices(Set.of(service.getId()))).thenReturn(Map.of());
        when(inventory.findSoftwareComponentsByIds(Set.of(vulnerable.getId())))
                .thenReturn(Map.of(vulnerable.getId(), vulnerable));

        var response = matching.matchChange(signalId);

        assertThat(response.matchedComponentCount()).isEqualTo(1);
        assertThat(response.impactedServices()).singleElement().satisfies(impact -> {
            assertThat(impact.confidence()).isEqualTo(MatchingDtos.MatchConfidence.POSSIBLE);
            assertThat(impact.evidence()).singleElement().satisfies(evidence -> {
                assertThat(evidence.confidence()).isEqualTo(MatchingDtos.MatchConfidence.POSSIBLE);
                assertThat(evidence.affectedProduct().versionMatch())
                        .isEqualTo(MatchingDtos.VersionMatch.UNDETERMINED);
            });
        });
    }

    private static ChangeSignal signal(
            UUID id, SignalType type, String sourceId, String externalId) {
        return new ChangeSignal(
                id, type, sourceId, externalId, externalId, "summary", null, null,
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
    }

    private static SoftwareComponent component(String name, String version) {
        return new SoftwareComponent(UUID.randomUUID(), "maven", name, version);
    }
}
