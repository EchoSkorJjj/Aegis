package com.aegis.core.inventory.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A person accountable for one or more business services: an organization member holding the
 * {@code SERVICE_OWNER} role in identity-service. One row per person per org, keyed by their
 * identity-service user id (the JWT {@code sub}) — which is what lets Aegis match an owner to a
 * signed-in user, route work to them, and check owner-only permissions.
 *
 * <p>{@code name} and {@code email} are a snapshot taken from identity-service, refreshed every time
 * the person is assigned to a service. They exist for display and notification, never for identity —
 * two owners may share a display name.
 */
@Entity
@Table(name = "owners")
public class Owner extends InventoryEntity {

    /** Longest display name kept (the column width); identity-service may allow longer. */
    public static final int MAX_NAME = 255;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, length = MAX_NAME)
    private String name;

    @Column(name = "email", length = 255)
    private String email;

    protected Owner() {
        // Required by JPA.
    }

    public Owner(UUID id, UUID userId, String name, String email) {
        super(id);
        this.userId = requireNonNull(userId, "userId");
        refreshProfile(name, email);
    }

    /** Re-take the display snapshot from identity-service (the person may have been renamed). */
    public void refreshProfile(String name, String email) {
        String text = requireText(name, "name");
        this.name = text.length() <= MAX_NAME ? text : text.substring(0, MAX_NAME);
        this.email = email == null || email.isBlank() ? null : email.trim();
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }
}
