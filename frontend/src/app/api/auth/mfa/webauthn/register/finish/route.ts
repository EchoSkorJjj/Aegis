import { NextResponse } from "next/server";

import { authedBackendFetch } from "@/lib/server/api";

/** Finish passkey registration (authenticated): verifies the attestation and stores the credential. */
export async function POST(request: Request) {
  const body = await request.text();
  const result = await authedBackendFetch("/auth/mfa/webauthn/register/finish", {
    method: "POST",
    body,
  });
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
