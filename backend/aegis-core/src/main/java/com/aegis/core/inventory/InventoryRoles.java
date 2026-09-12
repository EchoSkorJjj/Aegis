package com.aegis.core.inventory;

/** Method-security expressions for the Aegis domain roles carried in identity-service JWTs. */
public final class InventoryRoles {

    public static final String READ = "hasAnyRole('SERVICE_OWNER','ANALYST','COMPLIANCE_ADMIN')";
    public static final String WRITE = "hasAnyRole('ANALYST','COMPLIANCE_ADMIN')";

    private InventoryRoles() {
    }
}
