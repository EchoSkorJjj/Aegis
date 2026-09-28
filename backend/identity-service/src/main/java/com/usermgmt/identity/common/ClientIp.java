package com.usermgmt.identity.common;

import jakarta.servlet.http.HttpServletRequest;

/** Resolves the caller's IP address from a request. */
public final class ClientIp {

    private ClientIp() {
    }

    /**
     * Best-effort client IP: the first {@code X-Forwarded-For} hop if present, otherwise the socket
     * address. Behind the gateway that hop is the browser's address: the frontend's BFF forwards only
     * the address its load balancer recorded, and the gateway passes it on only from the BFF's
     * network, appending the BFF's own (see the gateway's {@code ForwardedForFilter}). A caller that
     * reaches this service directly could claim any address, so only the gateway and aegis-core
     * should be able to reach it.
     */
    public static String of(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
