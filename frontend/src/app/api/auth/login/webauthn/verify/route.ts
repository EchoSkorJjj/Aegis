import { NextResponse } from "next/server";

import { backendFetch } from "@/lib/server/backend";
import { writeSession } from "@/lib/server/session";
import type { TokenResponse } from "@/lib/types";

/**
 * Passkey login step 2 (public): verify the assertion for the pending challenge, then set the
 * httpOnly session cookies — the same way the emailed-code path completes a sign-in.
 */
export async function POST(request: Request) {
  const body = await request.json().catch(() => null);
  if (!body?.challengeId || !body?.credential) {
    return NextResponse.json(
      { message: "Challenge and credential are required" },
      { status: 400 },
    );
  }

  const result = await backendFetch("/auth/login/webauthn/verify", {
    method: "POST",
    body: JSON.stringify({
      challengeId: body.challengeId,
      credential: body.credential,
    }),
  });

  if (!result.ok) {
    const message =
      (result.data as { message?: string })?.message ?? "Verification failed";
    return NextResponse.json({ message }, { status: result.status });
  }

  await writeSession(result.data as TokenResponse);
  return NextResponse.json({ ok: true });
}
