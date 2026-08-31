import { NextResponse } from "next/server";

import { authedBackendFetch } from "@/lib/server/api";

/** Confirm authenticator enrollment with a code (authenticated); returns the backup codes. */
export async function POST(request: Request) {
  const body = await request.text();
  const result = await authedBackendFetch("/auth/mfa/totp/confirm", { method: "POST", body });
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
