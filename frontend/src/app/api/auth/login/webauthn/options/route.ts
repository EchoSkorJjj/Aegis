import { NextResponse } from "next/server";

import { backendFetch } from "@/lib/server/backend";

/** Passkey login step 1 (public): request assertion options for the pending login challenge. */
export async function POST(request: Request) {
  const body = await request.text();
  const result = await backendFetch("/auth/login/webauthn/options", {
    method: "POST",
    body,
  });
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
