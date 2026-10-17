import { NextResponse } from "next/server";

import { authedBackendFetch } from "@/lib/server/api";
import { writeSession } from "@/lib/server/session";
import type { TokenResponse } from "@/lib/types";

/**
 * Switches the caller's active organization (ADR 0001). Forwards to POST /auth/switch-org with the
 * current access token; the backend verifies membership and re-issues a token scoped to the target
 * org, which we persist as the new httpOnly session (like login). A 403 (not a member) passes
 * straight through, leaving the existing session intact.
 */
export async function POST(request: Request) {
  const body = await request.text();
  const result = await authedBackendFetch("/auth/switch-org", { method: "POST", body });
  if (!result.ok) {
    return NextResponse.json(result.data ?? {}, { status: result.status });
  }
  await writeSession(result.data as TokenResponse);
  return new NextResponse(null, { status: 204 });
}
