import { NextResponse } from "next/server";

import { backendFetch } from "@/lib/server/backend";

/**
 * Requests a password-reset email. The backend responds the same way whether or
 * not the address exists (no account enumeration), so we normalise any success
 * to a generic { ok: true }.
 */
export async function POST(request: Request) {
  const body = await request.json().catch(() => null);

  const result = await backendFetch("/auth/forgot-password", {
    method: "POST",
    body: JSON.stringify({ email: body?.email }),
  });

  if (result.ok) {
    return NextResponse.json({ ok: true });
  }

  return NextResponse.json(
    result.data ?? { message: "Request failed" },
    { status: result.status },
  );
}
