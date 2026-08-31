import { NextResponse } from "next/server";

import { authedBackendFetch } from "@/lib/server/api";

/** Regenerate the backup (recovery) codes (authenticated), re-verifying a current code. */
export async function POST(request: Request) {
  const body = await request.text();
  const result = await authedBackendFetch("/auth/mfa/recovery-codes/regenerate", {
    method: "POST",
    body,
  });
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
