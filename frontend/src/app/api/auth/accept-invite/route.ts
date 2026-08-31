import { NextResponse } from "next/server";

import { backendFetch } from "@/lib/server/backend";

/** Accept an admin invite (public): set the first password with the emailed token. */
export async function POST(request: Request) {
  const body = await request.text();
  const result = await backendFetch("/auth/accept-invite", { method: "POST", body });
  if (result.ok) {
    return new NextResponse(null, { status: 204 });
  }
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
