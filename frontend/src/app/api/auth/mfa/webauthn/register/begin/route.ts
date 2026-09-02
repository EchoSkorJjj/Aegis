import { NextResponse } from "next/server";

import { authedBackendFetch } from "@/lib/server/api";

/** Start passkey registration (authenticated): returns the WebAuthn creation options. */
export async function POST() {
  const result = await authedBackendFetch("/auth/mfa/webauthn/register/begin", {
    method: "POST",
  });
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
