import { NextResponse } from "next/server";

import { backendFetch } from "@/lib/server/backend";
import { clearSession, readRefreshToken } from "@/lib/server/session";

/**
 * Revokes the refresh token on the backend (best-effort) and clears the session
 * cookies. Always clears locally, even if the backend call fails.
 */
export async function POST() {
  const refreshToken = await readRefreshToken();
  if (refreshToken) {
    await backendFetch("/auth/logout", {
      method: "POST",
      body: JSON.stringify({ refreshToken }),
    });
  }

  await clearSession();
  return NextResponse.json({ ok: true });
}
