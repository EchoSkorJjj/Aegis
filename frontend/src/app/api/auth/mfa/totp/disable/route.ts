import { NextResponse } from "next/server";

import { authedBackendFetch } from "@/lib/server/api";

/** Turn the authenticator off (authenticated), re-verifying a current code. */
export async function POST(request: Request) {
  const body = await request.text();
  const result = await authedBackendFetch("/auth/mfa/totp/disable", { method: "POST", body });
  if (result.ok) {
    return new NextResponse(null, { status: 204 });
  }
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
