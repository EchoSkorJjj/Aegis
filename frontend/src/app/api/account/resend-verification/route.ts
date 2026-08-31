import { NextResponse } from "next/server";

import { backendFetch } from "@/lib/server/backend";
import { authedBackendFetch } from "@/lib/server/api";

/**
 * Re-send the signed-in user's email-verification link. We look up their address server-side
 * (from /users/me) and hand it to the public resend endpoint, so the client never supplies it.
 */
export async function POST() {
  const me = await authedBackendFetch("/users/me");
  if (!me.ok) {
    return NextResponse.json({ message: "Not authenticated" }, { status: 401 });
  }

  const email = (me.data as { email: string }).email;
  const result = await backendFetch("/auth/resend-verification", {
    method: "POST",
    body: JSON.stringify({ email }),
  });
  return new NextResponse(null, { status: result.ok ? 202 : result.status });
}
