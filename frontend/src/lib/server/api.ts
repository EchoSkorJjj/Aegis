import { NextResponse } from "next/server";

import { backendFetch, type BackendResult } from "@/lib/server/backend";
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
  const accessToken = await readAccessToken();
  if (accessToken) {
    const first = await backendFetch(path, { ...init, token: accessToken });
    if (first.status !== 401) {
      return first;
    }
    // 401 → access token expired; fall through to a refresh.
  }

  const refreshToken = await readRefreshToken();
  if (!refreshToken) {
    return { ok: false, status: 401, data: { message: "Not authenticated" } };
  }

  const refreshed = await backendFetch("/auth/refresh", {
    method: "POST",
    body: JSON.stringify({ refreshToken }),
  });
  if (!refreshed.ok) {
    await clearSession();
    return { ok: false, status: 401, data: { message: "Session expired" } };
  }

  const tokens = refreshed.data as TokenResponse;
  await writeSession(tokens);
  return backendFetch(path, { ...init, token: tokens.accessToken });
}

/** The route context of a `[[...path]]` catch-all BFF route. */
export type CatchAllRouteContext = { params: Promise<{ path?: string[] }> };

/**
 * The catch-all proxy shared by the BFF's GET-only domain routes (change signals, matching, scoring):
 * forwards `{prefix}/{...path}?{query}` to the gateway as the signed-in user and passes the backend's
 * status and body straight through (a 403 stays a 403). Dot segments are refused, so a crafted path
 * can't climb out of `prefix` on the gateway.
 */
export async function proxyReadOnlyGet(
  prefix: string,
  request: Request,
  ctx: CatchAllRouteContext,
): Promise<NextResponse> {
  const { path = [] } = await ctx.params;
  if (path.some((segment) => segment === "." || segment === "..")) {
    return NextResponse.json({ message: "Invalid path" }, { status: 400 });
  }
  const query = new URL(request.url).search;
  const suffix = path.length > 0 ? `/${path.join("/")}` : "";
  const result = await authedBackendFetch(`${prefix}${suffix}${query}`, { method: "GET" });
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
