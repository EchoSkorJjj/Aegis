import { NextResponse } from "next/server";

import { backendFetch } from "@/lib/server/backend";
import {
  clearSession,
  readAccessToken,
  readRefreshToken,
  writeSession,
} from "@/lib/server/session";
import type { TokenResponse, UserProfile } from "@/lib/types";

/**
 * Returns the current user's profile, transparently refreshing an expired access
 * token. This is the client's source of truth for "am I signed in?": the browser
 * holds only httpOnly cookies, so it asks the server.
 *
 * Flow: try the access token → on 401, rotate via the refresh token and retry →
 * if the refresh is dead, clear the cookies and report 401.
 */
export async function GET() {
  const accessToken = await readAccessToken();

  if (accessToken) {
    const me = await backendFetch("/users/me", { token: accessToken });
    if (me.ok) {
      return NextResponse.json(me.data as UserProfile);
    }
    if (me.status !== 401) {
      return NextResponse.json(
        { message: "Unable to load profile" },
        { status: me.status },
      );
    }
    // 401 → access token expired; fall through to a refresh.
  }

  const refreshToken = await readRefreshToken();
  if (!refreshToken) {
    return NextResponse.json({ message: "Not authenticated" }, { status: 401 });
  }

  const refreshed = await backendFetch("/auth/refresh", {
    method: "POST",
    body: JSON.stringify({ refreshToken }),
  });
  if (!refreshed.ok) {
    await clearSession();
    return NextResponse.json({ message: "Session expired" }, { status: 401 });
  }

  const tokens = refreshed.data as TokenResponse;
  await writeSession(tokens);

  const me = await backendFetch("/users/me", { token: tokens.accessToken });
  if (!me.ok) {
    return NextResponse.json(
      { message: "Unable to load profile" },
      { status: me.status },
    );
  }
  return NextResponse.json(me.data as UserProfile);
}
