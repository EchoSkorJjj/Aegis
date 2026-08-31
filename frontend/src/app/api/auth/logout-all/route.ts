import { NextResponse } from "next/server";

import { authedBackendFetch } from "@/lib/server/api";
import { clearSession } from "@/lib/server/session";

/**
 * "Log out everywhere": revokes all of the user's refresh tokens on the backend and clears this
 * browser's session cookies too.
 */
export async function POST() {
  const result = await authedBackendFetch("/auth/logout-all", { method: "POST" });
  await clearSession();
  return new NextResponse(null, { status: result.ok ? 204 : result.status });
}
