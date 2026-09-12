package com.aegis.core.inventory.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SoftwareComponentTest {

    @Test
    void requiresCompletePackageCoordinates() {
        UUID id = UUID.randomUUID();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SoftwareComponent(null, "Maven", "spring-core", "7.0.0"))
                .withMessage("id must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SoftwareComponent(id, null, "spring-core", "7.0.0"))
                .withMessage("ecosystem must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SoftwareComponent(id, " ", "spring-core", "7.0.0"))
                .withMessage("ecosystem must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SoftwareComponent(id, "Maven", null, "7.0.0"))
                .withMessage("name must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SoftwareComponent(id, "Maven", " ", "7.0.0"))
                .withMessage("name must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SoftwareComponent(id, "Maven", "spring-core", null))
                .withMessage("version must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SoftwareComponent(id, "Maven", "spring-core", " "))
                .withMessage("version must not be blank");
    }

    @Test
    void normalizesCoordinatesAndUsesIdentityEquality() {
        UUID id = UUID.randomUUID();
        SoftwareComponent component = new SoftwareComponent(id, " Maven ", " spring-core ", " 7.0.0 ");
        SoftwareComponent sameIdentity = new SoftwareComponent(id, "npm", "react", "19.0.0");

        assertThat(component.getEcosystem()).isEqualTo("Maven");
        assertThat(component.getName()).isEqualTo("spring-core");
        assertThat(component.getVersion()).isEqualTo("7.0.0");
        assertThat(component).isEqualTo(sameIdentity).hasSameHashCodeAs(sameIdentity);
    }
}
