import { NextResponse } from "next/server";

import { authedBackendFetch } from "@/lib/server/api";

/** The caller's MFA enrollment state (authenticated), for the settings page. */
export async function GET() {
  const result = await authedBackendFetch("/auth/mfa/status", { method: "GET" });
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
