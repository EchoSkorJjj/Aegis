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
