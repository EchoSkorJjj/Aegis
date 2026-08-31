import { NextResponse } from "next/server";

import { authedBackendFetch } from "@/lib/server/api";

/** Start authenticator enrollment (authenticated): returns the secret + otpauth URI. */
export async function POST() {
  const result = await authedBackendFetch("/auth/mfa/totp/init", { method: "POST" });
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
