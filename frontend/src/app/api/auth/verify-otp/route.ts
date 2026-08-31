import { NextResponse } from "next/server";

import { backendFetch } from "@/lib/server/backend";
import { writeSession } from "@/lib/server/session";
import type { TokenResponse } from "@/lib/types";

/**
 * Step 2 of login: exchange the challenge id + emailed code for tokens, then set
 * the httpOnly session cookies.
 */
export async function POST(request: Request) {
  const body = await request.json().catch(() => null);
  if (!body?.challengeId || !body?.code) {
    return NextResponse.json(
      { message: "Challenge and code are required" },
      { status: 400 },
    );
  }

  const result = await backendFetch("/auth/login/verify-otp", {
    method: "POST",
    body: JSON.stringify({ challengeId: body.challengeId, code: body.code }),
  });

  if (!result.ok) {
    const message =
      (result.data as { message?: string })?.message ?? "Verification failed";
    return NextResponse.json({ message }, { status: result.status });
  }

  await writeSession(result.data as TokenResponse);
  return NextResponse.json({ ok: true });
}
