package com.aegis.core.inventory.domain;

import java.util.Objects;
import java.util.UUID;

/** Base identity semantics shared by the immutable inventory entities. */
abstract class InventoryEntity {

    private final UUID id;

    protected InventoryEntity(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        this.id = id;
    }

    public final UUID getId() {
        return id;
    }

    protected static <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        return value;
    }

    protected static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        InventoryEntity that = (InventoryEntity) other;
        return id.equals(that.id);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(getClass(), id);
    }
}
