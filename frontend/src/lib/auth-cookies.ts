/**
 * Session cookie names + lifetimes, shared by the BFF route handlers and the
 * route guard (`proxy.ts`). This module deliberately has NO `next/headers`
 * import so it is safe to pull into the proxy/edge context — it holds constants
 * only, no I/O.
 */

/** Short-lived access JWT (matches backend identity.jwt.access-ttl-seconds). */
export const ACCESS_COOKIE = "um_access";

/** Long-lived rotating refresh token (matches identity.jwt.refresh-ttl-seconds). */
export const REFRESH_COOKIE = "um_refresh";

/** 7 days — the backend refresh-token TTL; the cookie should not outlive it. */
export const REFRESH_MAX_AGE_SECONDS = 60 * 60 * 24 * 7;
