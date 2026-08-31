import { NextResponse } from "next/server";

import { backendFetch } from "@/lib/server/backend";
import { writeSession } from "@/lib/server/session";
import type { TokenResponse } from "@/lib/types";

/**
 * Step 1 of login. Forwards the password check to the backend. The backend
 * returns EITHER an emailed-OTP challenge (2FA enabled) OR tokens directly
 * (2FA disabled). On a direct token response we set the httpOnly session
 * cookies here; on a challenge we hand the challenge id back to the client so
 * it can collect the code (no session is established yet).
 */
export async function POST(request: Request) {
  const body = await request.json().catch(() => null);
  if (!body?.email || !body?.password) {
    return NextResponse.json(
      { message: "Email and password are required" },
      { status: 400 },
    );
  }

  const result = await backendFetch("/auth/login", {
    method: "POST",
    body: JSON.stringify({ email: body.email, password: body.password }),
  });

  if (!result.ok) {
    const message =
      (result.data as { message?: string })?.message ?? "Login failed";
    return NextResponse.json({ message }, { status: result.status });
  }

  const data = result.data as Partial<TokenResponse> & {
    challengeId?: string;
    method?: string;
    methods?: string[];
  };

  if (data.challengeId) {
    return NextResponse.json({
      mfaRequired: true,
      challengeId: data.challengeId,
      method: data.method ?? "EMAIL_OTP",
      methods: data.methods ?? (data.method ? [data.method] : ["EMAIL_OTP"]),
    });
  }

  await writeSession(data as TokenResponse);
  return NextResponse.json({ mfaRequired: false });
}
