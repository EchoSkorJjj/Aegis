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
 * Every server-side backend call goes through the API gateway, which authenticates the request and
 * routes it (`/auth`, `/oauth2`, `/users` -> identity-service; `/api` -> aegis-core). The browser
 * talks only to our same-origin BFF; the BFF talks only to the gateway.
 *
 * - Docker Compose: GATEWAY_URL=http://gateway:8080 (the gateway's service DNS name).
 * - Local `pnpm dev`: defaults to http://localhost:8080 — run the gateway locally, or point
 *   GATEWAY_URL at wherever it listens.
 */
export const BACKEND_URL = process.env.GATEWAY_URL ?? "http://localhost:8080";

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
 * How many proxies in front of this server append to X-Forwarded-For: `TRUSTED_PROXY_HOPS`, default 1
 * (an AWS load balancer; CloudFront in front of it makes 2). With no proxy at all (local dev), 1 still
 * works: Next.js fills the header in with the caller's address when a request arrives without one.
 */
function trustedProxyHops(): number {
  const hops = Number.parseInt(process.env.TRUSTED_PROXY_HOPS ?? "", 10);
  return Number.isInteger(hops) && hops >= 1 ? hops : 1;
}

/**
 * The caller's address as the outermost trusted proxy recorded it. Each proxy appends the address a
 * request came from, so only the last `hops` entries of X-Forwarded-For were written by proxies;
 * anything before them came from the client, who can write whatever it likes there.
 */
function clientAddress(forwardedFor: string | null, hops: number): string | undefined {
  const chain = (forwardedFor ?? "")
    .split(",")
    .map((entry) => entry.trim())
    .filter(Boolean);
  return chain[Math.max(chain.length - hops, 0)];
}

/**
 * Propagates the caller's address to the backend so identity-service's rate limiter and audit log see
 * the real client, not this BFF: one address, the one the proxies recorded, never the part of the
 * chain the client wrote (it could dodge the per-IP limit on /auth by claiming a new address every
 * time). The gateway passes it on only from the BFF's network (see its ForwardedForFilter).
 */
async function forwardedFor(): Promise<Record<string, string>> {
  try {
    const inbound = await nextHeaders();
    const address = clientAddress(inbound.get("x-forwarded-for"), trustedProxyHops());
    return address ? { "X-Forwarded-For": address } : {};
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

/** A file from the backend: its bytes and headers when it answered 2xx, else its error as `data`. */
export interface BackendDownload extends BackendResult {
  body: ArrayBuffer | null;
  headers: Headers | null;
}

/**
 * Fetches a file (a PDF, a CSV) from the backend as the given user: the bytes as they came, never parsed as text
 * like `backendFetch` does. A non-2xx answer is read as the usual JSON error, so route handlers can pass it on.
 */
export async function backendDownload(path: string, token: string): Promise<BackendDownload> {
  const forwarded = await forwardedFor();
  let response: Response;
  try {
    response = await fetch(`${BACKEND_URL}${path}`, {
      headers: { ...forwarded, Authorization: `Bearer ${token}` },
      cache: "no-store",
    });
  } catch {
    return { ok: false, status: 503, data: { message: "Service unavailable" }, body: null, headers: null };
  }
  if (response.ok) {
    return { ok: true, status: response.status, data: null, body: await response.arrayBuffer(), headers: response.headers };
  }
  let data: unknown = null;
  const text = await response.text();
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = { message: text };
  }
  return { ok: false, status: response.status, data, body: null, headers: null };
}
