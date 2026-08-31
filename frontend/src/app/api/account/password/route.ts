import { NextResponse } from "next/server";

import { authedBackendFetch } from "@/lib/server/api";

/**
 * Change the signed-in user's password. Proxies POST /auth/change-password with the access token;
 * the backend re-checks the current password and revokes all sessions on success.
 */
export async function POST(request: Request) {
  const body = await request.text();
  const result = await authedBackendFetch("/auth/change-password", { method: "POST", body });
  if (result.ok) {
    return new NextResponse(null, { status: 204 });
  }
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
