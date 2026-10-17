import { cache } from "react";

import { BACKEND_URL } from "@/lib/server/backend";
import type { AuthConfig } from "@/lib/types";

/**
 * Server-side read of the public auth config (login mode, self-registration, MFA methods), so one
 * backend flag drives the server-rendered UI too. Deduped per request (React cache).
 *
 * `no-store` (not `revalidate`) is deliberate: it opts the calling pages out of build-time static
 * prerendering. This config is an operational toggle set by the running backend, and the backend is
 * NOT reachable during `docker build`/CI — a prerendered page would bake the unreachable fallback
 * (registration "on") and only self-heal seconds after the first post-deploy hit. Rendering these
 * pages on demand keeps the UI in lockstep with the live backend. The call is a cheap in-cluster
 * hop; the backend remains the source of truth and the actual enforcer. Falls back to sensible
 * defaults if the backend is unreachable, so pages still render.
 */
export const getAuthConfig = cache(async (): Promise<AuthConfig> => {
  try {
    const res = await fetch(`${BACKEND_URL}/auth/config`, { cache: "no-store" });
    if (res.ok) {
      return (await res.json()) as AuthConfig;
    }
  } catch {
    // backend unreachable — fall through to defaults
  }
  return {
    mode: "local",
    registrationEnabled: true,
    organizationSignupEnabled: true,
    methods: { emailOtp: true, totp: false, webauthn: false },
  };
});
