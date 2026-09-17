import { NextResponse } from "next/server";

import { authedBackendFetch } from "@/lib/server/api";

/**
 * BFF proxy for the aegis-core inventory API (org-scoped — ADR §6). Forwards every request under
 * `/api/inventory/**` to the gateway with the caller's httpOnly access token attached server-side.
 * aegis-core enforces the domain read/write roles and the org scoping; its 403/404/409 come straight
 * back. The route is fixed at `/api/inventory`, so the captured path can only address that subtree.
 */
type RouteParams = { params: Promise<{ path?: string[] }> };

async function proxy(request: Request, ctx: RouteParams, method: string) {
  const { path = [] } = await ctx.params;
  // Defence in depth against path-traversal in the captured segments (the router normalises URLs,
  // but never forward a "." / ".." out of the /api/inventory subtree).
  if (path.some((segment) => segment === "." || segment === "..")) {
    return NextResponse.json({ message: "Invalid path" }, { status: 400 });
  }
  const query = new URL(request.url).search;
  const suffix = path.length > 0 ? `/${path.join("/")}` : "";
  const withBody = method === "POST" || method === "PUT" || method === "PATCH";
  const result = await authedBackendFetch(`/api/inventory${suffix}${query}`, {
    method,
    ...(withBody ? { body: await request.text() } : {}),
  });
  if (result.status === 204 || (result.ok && result.data == null)) {
    return new NextResponse(null, { status: result.ok ? 204 : result.status });
  }
  return NextResponse.json(result.data ?? {}, { status: result.status });
}

export function GET(request: Request, ctx: RouteParams) {
  return proxy(request, ctx, "GET");
}

export function POST(request: Request, ctx: RouteParams) {
  return proxy(request, ctx, "POST");
}

export function PUT(request: Request, ctx: RouteParams) {
  return proxy(request, ctx, "PUT");
}

export function DELETE(request: Request, ctx: RouteParams) {
  return proxy(request, ctx, "DELETE");
}
