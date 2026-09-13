package com.aegis.core.inventory.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

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
    }

    @Test
    void normalizesNameAndUsesIdentityEquality() {
        UUID id = UUID.randomUUID();
        BusinessService service = new BusinessService(id, "  Payments  ", BusinessCriticality.CRITICAL);
        BusinessService sameIdentity = new BusinessService(id, "Checkout", BusinessCriticality.HIGH);

        assertThat(service.getName()).isEqualTo("Payments");
        assertThat(service).isEqualTo(sameIdentity).hasSameHashCodeAs(sameIdentity);
    }
}
