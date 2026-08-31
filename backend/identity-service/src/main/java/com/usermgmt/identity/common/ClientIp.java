package com.usermgmt.identity.common;

import jakarta.servlet.http.HttpServletRequest;

/** Resolves the caller's IP address from a request. */
public final class ClientIp {

    private ClientIp() {
    }

    /**
     * Best-effort client IP: the first {@code X-Forwarded-For} hop (set by the BFF / load
     * balancer in front of the service) if present, otherwise the socket address. Trust the
     * forwarded header only as far as your proxy is trusted to set it.
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
