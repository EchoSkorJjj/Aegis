import { cookies } from "next/headers";

import {
  ACCESS_COOKIE,
  REFRESH_COOKIE,
  REFRESH_MAX_AGE_SECONDS,
} from "@/lib/auth-cookies";
import type { TokenResponse } from "@/lib/types";

/**
 * Reads/writes the httpOnly session cookies. Only usable from Route Handlers and
 * Server Functions (anywhere `cookies()` can mutate the outgoing response).
 */

// `Secure` cookies are rejected by browsers (notably Safari) over plain HTTP, so this can't key off
// NODE_ENV — `next start` forces that to "production" even for local HTTP, which would set Secure and
// silently drop the session in Safari on http://localhost. Gate it on an explicit flag instead:
// off by default (works on http://localhost everywhere), set AUTH_COOKIE_SECURE=true behind HTTPS.
const secure = process.env.AUTH_COOKIE_SECURE === "true";

function options(maxAge: number) {
  return {
    httpOnly: true,
    sameSite: "lax" as const,
    secure,
    path: "/",
    maxAge,
  };
}

/** Persists the access + refresh tokens as httpOnly cookies. */
export async function writeSession(tokens: TokenResponse) {
  const store = await cookies();
  store.set(ACCESS_COOKIE, tokens.accessToken, options(tokens.expiresInSeconds));
  store.set(REFRESH_COOKIE, tokens.refreshToken, options(REFRESH_MAX_AGE_SECONDS));
}

/** Clears both session cookies (logout / dead session). */
export async function clearSession() {
  const store = await cookies();
  store.delete(ACCESS_COOKIE);
  store.delete(REFRESH_COOKIE);
}

export async function readAccessToken(): Promise<string | null> {
  return (await cookies()).get(ACCESS_COOKIE)?.value ?? null;
}

export async function readRefreshToken(): Promise<string | null> {
  return (await cookies()).get(REFRESH_COOKIE)?.value ?? null;
}
