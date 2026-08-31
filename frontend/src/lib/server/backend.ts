import { headers as nextHeaders } from "next/headers";

/**
 * Server-only transport to the identity-service backend.
 *
 * The browser never calls the backend directly — it calls our same-origin
 * `/api/auth/*` BFF (see `app/api/auth`), which forwards here with the access
 * token read from an httpOnly cookie. Keeping the token server-side is the whole
 * point of the BFF: it is never exposed to client JavaScript.
 */

/**
 * Backend base URL as seen from the Next.js server (NOT the browser).
 *
 * - Local `pnpm dev`: defaults to http://localhost:8081.
 * - Docker Compose: set IDENTITY_API_URL=http://backend:8081 (service DNS name).
 *
 * Falls back to NEXT_PUBLIC_API_URL for backwards compatibility with existing
 * setups, but note that in Docker the browser-facing localhost URL is not
 * reachable from inside the frontend container, so IDENTITY_API_URL must win.
 */
export const BACKEND_URL =
  process.env.IDENTITY_API_URL ??
  process.env.NEXT_PUBLIC_API_URL ??
  "http://localhost:8081";

export interface BackendResult {
  /** True for 2xx responses. */
  ok: boolean;
  status: number;
  /** Parsed JSON body, or null for empty / 204 responses. */
  data: unknown;
}

interface BackendRequest extends RequestInit {
  /** Bearer token to attach to the backend request. */
  token?: string | null;
}

/**
 * Propagates the caller's IP to the backend so its audit log and rate limiter see the real
 * client, not this BFF. Forwards the inbound X-Forwarded-For chain (set by the load balancer /
 * ingress in front of Next) or X-Real-IP; trust it only as far as that proxy is trusted to set
 * it. In local dev with no proxy there is neither, and the backend simply sees the BFF.
 */
async function forwardedFor(): Promise<Record<string, string>> {
  try {
    const inbound = await nextHeaders();
    const value = inbound.get("x-forwarded-for") ?? inbound.get("x-real-ip");
    return value ? { "X-Forwarded-For": value } : {};
  } catch {
    // headers() is only available within a request scope; ignore otherwise.
    return {};
  }
}

/**
 * Calls the backend and normalises the result into `{ ok, status, data }`.
 * Never throws on a non-2xx status — the caller decides how to react — and maps
 * an unreachable backend to a 503 so route handlers can respond gracefully.
 */
export async function backendFetch(
  path: string,
  { token, headers, ...init }: BackendRequest = {},
): Promise<BackendResult> {
  const forwarded = await forwardedFor();
  let response: Response;
  try {
    response = await fetch(`${BACKEND_URL}${path}`, {
      ...init,
      headers: {
        "Content-Type": "application/json",
        ...forwarded,
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...headers,
      },
      cache: "no-store",
    });
  } catch {
    return { ok: false, status: 503, data: { message: "Service unavailable" } };
  }

  let data: unknown = null;
  if (response.status !== 204) {
    const text = await response.text();
    if (text) {
      try {
        data = JSON.parse(text);
      } catch {
        // Non-JSON body — surface it as a message.
        data = { message: text };
      }
    }
  }

  return { ok: response.ok, status: response.status, data };
}
