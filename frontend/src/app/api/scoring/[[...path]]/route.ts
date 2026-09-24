import { NextResponse } from "next/server";

import { authedBackendFetch } from "@/lib/server/api";

/**
 * BFF proxy for the read-only aegis-core scoring API (Epic 7). Forwards GET `/api/scoring/**` — one
 * change signal scored per service (`/change-signals/{id}`) or the estate roll-up (`/rollups`) — to
 * the gateway with the caller's httpOnly token; aegis-core scopes the score to the caller's active org
 * and enforces the domain read roles (403 passed through).
 */
type RouteParams = { params: Promise<{ path?: string[] }> };

export async function GET(request: Request, ctx: RouteParams) {
  const { path = [] } = await ctx.params;
  if (path.some((segment) => segment === "." || segment === "..")) {
    return NextResponse.json({ message: "Invalid path" }, { status: 400 });
  }
  const query = new URL(request.url).search;
  const suffix = path.length > 0 ? `/${path.join("/")}` : "";
  const result = await authedBackendFetch(`/api/scoring${suffix}${query}`, { method: "GET" });
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
