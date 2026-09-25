package com.aegis.core.inventory.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BusinessServiceTest {

    @Test
    void definesStableCriticalityValues() {
        assertThat(BusinessCriticality.values())
                .containsExactly(
                        BusinessCriticality.LOW,
                        BusinessCriticality.MEDIUM,
                        BusinessCriticality.HIGH,
                        BusinessCriticality.CRITICAL);
    }

    @Test
    void definesStableExposureValuesLeastToMostExposed() {
        assertThat(ServiceExposure.values())
                .containsExactly(
                        ServiceExposure.ISOLATED,
                        ServiceExposure.INTERNAL,
                        ServiceExposure.INTERNET_FACING);
    }

    @Test
    void defaultsExposureToInternalWhenTheOwnerDeclaredNone() {
        BusinessService service = new BusinessService(UUID.randomUUID(), "Payments", BusinessCriticality.CRITICAL);

        assertThat(service.getExposure()).isEqualTo(ServiceExposure.INTERNAL);
        assertThat(BusinessService.DEFAULT_EXPOSURE).isEqualTo(ServiceExposure.INTERNAL);
    }

    @Test
    void keepsTheCurrentExposureWhenAnUpdateDoesNotStateOne() {
        BusinessService service = new BusinessService(
                UUID.randomUUID(), "Portal", BusinessCriticality.HIGH, ServiceExposure.INTERNET_FACING);

        service.update("Portal", BusinessCriticality.MEDIUM);

        // A PUT that omits exposure must not quietly downgrade a service the owner declared public.
        assertThat(service.getExposure()).isEqualTo(ServiceExposure.INTERNET_FACING);
        assertThat(service.getCriticality()).isEqualTo(BusinessCriticality.MEDIUM);

        service.update("Portal", BusinessCriticality.MEDIUM, ServiceExposure.ISOLATED);
        assertThat(service.getExposure()).isEqualTo(ServiceExposure.ISOLATED);
    }

    @Test
    void requiresIdentityNameAndCriticality() {
        UUID id = UUID.randomUUID();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BusinessService(null, "Payments", BusinessCriticality.CRITICAL))
                .withMessage("id must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BusinessService(id, null, BusinessCriticality.CRITICAL))
                .withMessage("name must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BusinessService(id, "  ", BusinessCriticality.CRITICAL))
                .withMessage("name must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BusinessService(id, "Payments", null))
                .withMessage("criticality must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BusinessService(id, "Payments", BusinessCriticality.CRITICAL, null))
                .withMessage("exposure must not be null");
    }

    @Test
    void normalizesNameAndUsesIdentityEquality() {
        UUID id = UUID.randomUUID();
        BusinessService service = new BusinessService(id, "  Payments  ", BusinessCriticality.CRITICAL);
        BusinessService sameIdentity = new BusinessService(id, "Checkout", BusinessCriticality.HIGH);

        assertThat(service.getName()).isEqualTo("Payments");
        assertThat(service).isEqualTo(sameIdentity).hasSameHashCodeAs(sameIdentity);
    }

    @Test
    void anSbomImportIsForgottenWhenTheLinkPointsElsewhereOrIsRemoved() {
        BusinessService service = new BusinessService(UUID.randomUUID(), "Imported", BusinessCriticality.HIGH);
        RepositoryUrl url = RepositoryUrl.parse("https://github.com/a/b", List.of("github.com"));
        service.linkRepository(url, "main");
        Instant at = Instant.parse("2026-09-25T10:00:00Z");
        service.recordSbomImport(SbomImportSource.TOKEN, at);

        service.linkRepository(RepositoryUrl.parse("https://github.com/a/b.git", List.of("github.com")), " main ");
        assertThat(service.getSbomImportSource()).isEqualTo(SbomImportSource.TOKEN);
        assertThat(service.getSbomImportedAt()).isEqualTo(at);

        service.linkRepository(url, "develop");
        assertThat(service.getSbomImportSource()).isNull();
        assertThat(service.getSbomImportedAt()).isNull();

        service.recordSbomImport(SbomImportSource.PUBLIC, at);
        service.unlinkRepository();
        assertThat(service.getSbomImportSource()).isNull();
        assertThat(service.getSbomImportedAt()).isNull();
    }
}
