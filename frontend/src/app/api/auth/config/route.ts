import { NextResponse } from "next/server";

import { backendFetch } from "@/lib/server/backend";

/**
 * Public auth configuration (login mode + which MFA methods are enabled). Proxies GET /auth/config
 * so the SPA derives its behaviour from the backend — flipping one backend flag drives both sides.
 */
export async function GET() {
  const result = await backendFetch("/auth/config", { method: "GET" });
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
