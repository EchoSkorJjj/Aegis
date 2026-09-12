package com.aegis.core.inventory.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ThirdPartyVendorTest {

    @Test
    void requiresIdentityAndName() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ThirdPartyVendor(null, "Acme"))
                .withMessage("id must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ThirdPartyVendor(UUID.randomUUID(), null))
                .withMessage("name must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ThirdPartyVendor(UUID.randomUUID(), "\t"))
                .withMessage("name must not be blank");
    }

    @Test
    void normalizesNameAndUsesIdentityEquality() {
        UUID id = UUID.randomUUID();
        ThirdPartyVendor vendor = new ThirdPartyVendor(id, "  Acme  ");
        ThirdPartyVendor sameIdentity = new ThirdPartyVendor(id, "Renamed Acme");

        assertThat(vendor.getName()).isEqualTo("Acme");
        assertThat(vendor).isEqualTo(sameIdentity).hasSameHashCodeAs(sameIdentity);
    }
}
