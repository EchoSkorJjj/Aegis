import { NextResponse } from "next/server";

import { backendFetch } from "@/lib/server/backend";

/**
 * Confirms an email address using the single-use token from the verification
 * link. Surfaces the backend status (e.g. 400 for an invalid/expired token).
 */
export async function POST(request: Request) {
  const body = await request.json().catch(() => null);
  if (!body?.token) {
    return NextResponse.json({ message: "Token is required" }, { status: 400 });
  }

  const result = await backendFetch("/auth/verify-email", {
    method: "POST",
    body: JSON.stringify({ token: body.token }),
  });

  if (result.ok) {
    return NextResponse.json({ ok: true });
  }

  return NextResponse.json(
    result.data ?? { message: "Verification failed" },
    { status: result.status },
  );
}
