import { NextResponse } from "next/server";

import { backendFetch } from "@/lib/server/backend";

/**
 * Completes a password reset using the single-use token from the email link.
 * Surfaces the backend status (e.g. 400 for an invalid/expired token).
 */
export async function POST(request: Request) {
  const body = await request.json().catch(() => null);
  if (!body?.token || !body?.newPassword) {
    return NextResponse.json(
      { message: "Token and new password are required" },
      { status: 400 },
    );
  }

  const result = await backendFetch("/auth/reset-password", {
    method: "POST",
    body: JSON.stringify({ token: body.token, newPassword: body.newPassword }),
  });

  if (result.ok) {
    return NextResponse.json({ ok: true });
  }

  return NextResponse.json(
    result.data ?? { message: "Reset failed" },
    { status: result.status },
  );
}
