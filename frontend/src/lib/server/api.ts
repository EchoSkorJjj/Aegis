import { NextResponse } from "next/server";

import {
  backendDownload,
  backendFetch,
  type BackendDownload,
  type BackendResult,
} from "@/lib/server/backend";
import {
  clearSession,
  readAccessToken,
  readRefreshToken,
  writeSession,
} from "@/lib/server/session";
import type { TokenResponse } from "@/lib/types";

/**
 * Calls the backend as the signed-in user, transparently rotating an expired access token via
 * the refresh cookie (the same dance `/api/auth/me` performs for the session probe). A 401 here
 * means "not, or no longer, authenticated" and the session cookies have been cleared.
 *
 * Use this from any authenticated BFF route that proxies a backend call on the user's behalf.
 */
export async function authedBackendFetch(
  path: string,
  init: RequestInit = {},
): Promise<BackendResult> {
  return asSignedInUser(
    (token) => backendFetch(path, { ...init, token }),
    (message) => ({ ok: false, status: 401, data: { message } }),
  );
}

/**
 * As `authedBackendFetch`, for a file (the evidence PDF or CSV): its bytes and headers as the backend sent them.
 */
export async function authedBackendDownload(path: string): Promise<BackendDownload> {
  return asSignedInUser(
    (token) => backendDownload(path, token),
    (message) => ({ ok: false, status: 401, data: { message }, body: null, headers: null }),
  );
}

/**
 * Makes `call` with the user's access token, and once more with a fresh one if the backend answers 401 (the token
 * expired). `signedOut` is the answer when there's no session, or it can't be renewed (the cookies are then cleared).
 */
async function asSignedInUser<T extends { status: number }>(
  call: (token: string) => Promise<T>,
  signedOut: (message: string) => T,
): Promise<T> {
  const accessToken = await readAccessToken();
  if (accessToken) {
    const first = await call(accessToken);
    if (first.status !== 401) {
      return first;
    }
    // 401 → access token expired; fall through to a refresh.
  }

  const refreshToken = await readRefreshToken();
  if (!refreshToken) {
    return signedOut("Not authenticated");
  }

  const refreshed = await backendFetch("/auth/refresh", {
    method: "POST",
    body: JSON.stringify({ refreshToken }),
  });
  if (!refreshed.ok) {
    await clearSession();
    return signedOut("Session expired");
  }

  const tokens = refreshed.data as TokenResponse;
  await writeSession(tokens);
  return call(tokens.accessToken);
}

/** The route context of a `[[...path]]` catch-all BFF route. */
export type CatchAllRouteContext = { params: Promise<{ path?: string[] }> };

/**
 * The gateway path for `{prefix}/{...path}`, or null when it would leave `prefix`. The router can hand
 * over a segment still percent-encoded (`%2e%2e`) or decoded into a path (`../users` from `..%2Fusers`),
 * and `fetch` resolves either as a dot segment — so the check runs on the path as `fetch` resolves it,
 * not on the raw segments.
 */
function gatewayPath(prefix: string, path: string[]): string | null {
  if (path.some((segment) => segment === "." || segment === "..")) return null;
  const joined = path.length > 0 ? `${prefix}/${path.join("/")}` : prefix;
  const resolved = new URL(joined, "http://gateway.invalid").pathname;
  return resolved === prefix || resolved.startsWith(`${prefix}/`) ? joined : null;
}

/**
 * The catch-all proxy shared by the BFF's GET-only domain routes (change signals, matching, scoring):
 * forwards `{prefix}/{...path}?{query}` to the gateway as the signed-in user and passes the backend's
 * status and body straight through (a 403 stays a 403). A path that would climb out of `prefix` on the
 * gateway is refused.
 */
export async function proxyReadOnlyGet(
  prefix: string,
  request: Request,
  ctx: CatchAllRouteContext,
): Promise<NextResponse> {
  const { path = [] } = await ctx.params;
  const target = gatewayPath(prefix, path);
  if (!target) {
    return NextResponse.json({ message: "Invalid path" }, { status: 400 });
  }
  const query = new URL(request.url).search;
  const result = await authedBackendFetch(`${target}${query}`, { method: "GET" });
  return NextResponse.json(result.data ?? {}, { status: result.status });
}

/**
 * The catch-all proxy for the BFF's write-capable domain routes (inventory, actions): forwards
 * `{prefix}/{...path}?{query}` with the request's method — and its body, for POST/PUT/PATCH — to the
 * gateway as the signed-in user, passing the backend's status and body straight through (204 stays
 * empty). A path that would climb out of `prefix` on the gateway is refused.
 */
export async function proxyToGateway(
  prefix: string,
  request: Request,
  ctx: CatchAllRouteContext,
  method: string,
): Promise<NextResponse> {
  const { path = [] } = await ctx.params;
  const target = gatewayPath(prefix, path);
  if (!target) {
    return NextResponse.json({ message: "Invalid path" }, { status: 400 });
  }
  const query = new URL(request.url).search;
  const withBody = method === "POST" || method === "PUT" || method === "PATCH";
  const result = await authedBackendFetch(`${target}${query}`, {
    method,
    ...(withBody ? { body: await request.text() } : {}),
  });
  if (result.status === 204 || (result.ok && result.data == null)) {
    return new NextResponse(null, { status: result.ok ? 204 : result.status });
  }
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
