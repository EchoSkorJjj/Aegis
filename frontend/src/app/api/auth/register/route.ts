import { NextResponse } from "next/server";

import { backendFetch } from "@/lib/server/backend";

/**
 * Creates an account. No session is established — the user still signs in
 * afterwards (and, with 2FA on, completes the emailed-code step). Passes the
 * backend status through so the client can surface 409 (email taken) etc.
 */
export async function POST(request: Request) {
  const body = await request.json().catch(() => null);

  const result = await backendFetch("/auth/register", {
    method: "POST",
    body: JSON.stringify(body ?? {}),
  });

  return NextResponse.json(result.data ?? {}, { status: result.status });
}
