package com.aegis.core.inventory.domain;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class OwnerTest {

    @Test
    void requiresIdentityAndName() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Owner(null, "Platform Team"))
                .withMessage("id must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Owner(UUID.randomUUID(), null))
                .withMessage("name must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Owner(UUID.randomUUID(), "\n"))
                .withMessage("name must not be blank");
    }

    @Test
    void normalizesNameAndUsesIdentityEqualityWithoutExposingContactDetails() {
        UUID id = UUID.randomUUID();
        Owner owner = new Owner(id, "  Platform Team  ");
        Owner sameIdentity = new Owner(id, "Core Platform Team");

        assertThat(owner.getName()).isEqualTo("Platform Team");
        assertThat(owner).isEqualTo(sameIdentity).hasSameHashCodeAs(sameIdentity);
    }

    @Test
    void differentEntityTypesAreNeverEqualEvenWhenIdsMatch() {
        UUID id = UUID.randomUUID();

        assertThat(new Owner(id, "Platform Team"))
                .isNotEqualTo(new ThirdPartyVendor(id, "Platform Team"));
    }
}
