package com.aegis.core.matching;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.aegis.core.common.ApiExceptions;
import com.aegis.core.ingestion.advisory.Advisory;
import com.aegis.core.ingestion.advisory.AdvisoryStore;
import com.aegis.core.ingestion.advisory.AffectedProduct;
import com.aegis.core.ingestion.incident.VendorIncident;
import com.aegis.core.ingestion.incident.VendorIncidentStore;
import com.aegis.core.ingestion.signal.ChangeSignal;
import com.aegis.core.ingestion.signal.ChangeSignal.SignalType;
import com.aegis.core.ingestion.signal.ChangeSignalStore;
import com.aegis.core.inventory.InventoryStore;
import com.aegis.core.inventory.InventoryStore.ComponentDependencyLink;
import com.aegis.core.inventory.InventoryStore.ServiceComponentLink;
import com.aegis.core.inventory.domain.BusinessCriticality;
import com.aegis.core.inventory.domain.BusinessService;
import com.aegis.core.inventory.domain.Owner;
import com.aegis.core.inventory.domain.SoftwareComponent;
import com.aegis.core.inventory.domain.ThirdPartyVendor;
import com.aegis.core.matching.MatchingDtos.EvidenceKind;
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
