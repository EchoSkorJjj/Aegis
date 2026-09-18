import { NextResponse } from "next/server";

import { authedBackendFetch } from "@/lib/server/api";

/**
 * BFF proxy for the read-only aegis-core matching API (Epic 6). Forwards
 * GET `/api/matching/change-signals/{id}` to the gateway with the caller's httpOnly token; aegis-core
 * scopes the match to the caller's active org and enforces the domain read roles (403 passed through).
 */
type RouteParams = { params: Promise<{ path?: string[] }> };

export async function GET(request: Request, ctx: RouteParams) {
  const { path = [] } = await ctx.params;
  if (path.some((segment) => segment === "." || segment === "..")) {
    return NextResponse.json({ message: "Invalid path" }, { status: 400 });
  }
  const query = new URL(request.url).search;
  const suffix = path.length > 0 ? `/${path.join("/")}` : "";
  const result = await authedBackendFetch(`/api/matching${suffix}${query}`, { method: "GET" });
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
