import { NextResponse } from "next/server";

import { authedBackendFetch } from "@/lib/server/api";

/** List the caller's registered passkeys (authenticated). */
export async function GET() {
  const result = await authedBackendFetch("/auth/mfa/webauthn/credentials");
  return NextResponse.json(result.data ?? [], { status: result.status });
}
