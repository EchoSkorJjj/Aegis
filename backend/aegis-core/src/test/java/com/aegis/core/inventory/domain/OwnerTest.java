package com.aegis.core.inventory.domain;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class OwnerTest {

    @Test
    void requiresIdentityTheUserItStandsForAndAName() {
        UUID userId = UUID.randomUUID();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Owner(null, userId, "Platform Team", null))
                .withMessage("id must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Owner(UUID.randomUUID(), null, "Platform Team", null))
                .withMessage("userId must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Owner(UUID.randomUUID(), userId, null, null))
                .withMessage("name must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Owner(UUID.randomUUID(), userId, "\n", null))
                .withMessage("name must not be blank");
    }

    @Test
    void normalizesTheDisplaySnapshotAndUsesIdentityEquality() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Owner owner = new Owner(id, userId, "  Jane Doe  ", "  jane@example.com ");
        Owner sameIdentity = new Owner(id, userId, "Jane D.", null);

        assertThat(owner.getUserId()).isEqualTo(userId);
        assertThat(owner.getName()).isEqualTo("Jane Doe");
        assertThat(owner.getEmail()).isEqualTo("jane@example.com");
        assertThat(owner).isEqualTo(sameIdentity).hasSameHashCodeAs(sameIdentity);
    }

    @Test
    void refreshesItsSnapshotFromIdentityAndTreatsABlankEmailAsNone() {
        Owner owner = new Owner(UUID.randomUUID(), UUID.randomUUID(), "Jane Doe", "jane@example.com");

        owner.refreshProfile("Jane Smith", "  ");

        assertThat(owner.getName()).isEqualTo("Jane Smith");
        assertThat(owner.getEmail()).isNull();
    }

    @Test
    void capsAnOverlongDisplayNameToTheColumnWidth() {
        Owner owner = new Owner(UUID.randomUUID(), UUID.randomUUID(), "x".repeat(Owner.MAX_NAME + 40), null);

        assertThat(owner.getName()).hasSize(Owner.MAX_NAME);
    }

    @Test
    void differentEntityTypesAreNeverEqualEvenWhenIdsMatch() {
        UUID id = UUID.randomUUID();

        assertThat(new Owner(id, UUID.randomUUID(), "Platform Team", null))
                .isNotEqualTo(new ThirdPartyVendor(id, "Platform Team"));
    }
}
