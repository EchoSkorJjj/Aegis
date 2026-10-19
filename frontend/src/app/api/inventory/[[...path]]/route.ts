import { proxyToGateway, type CatchAllRouteContext } from "@/lib/server/api";

/**
 * BFF proxy for the aegis-core inventory API (org-scoped — ADR §6). Forwards every request under
 * `/api/inventory/**` to the gateway with the caller's httpOnly access token attached server-side.
 * aegis-core enforces the domain read/write roles and the org scoping; its 403/404/409 come straight
 * back. The route is fixed at `/api/inventory`, so the captured path can only address that subtree.
 */
export function GET(request: Request, ctx: CatchAllRouteContext) {
  return proxyToGateway("/api/inventory", request, ctx, "GET");
}

export function POST(request: Request, ctx: CatchAllRouteContext) {
  return proxyToGateway("/api/inventory", request, ctx, "POST");
}

export function PUT(request: Request, ctx: CatchAllRouteContext) {
  return proxyToGateway("/api/inventory", request, ctx, "PUT");
}

export function DELETE(request: Request, ctx: CatchAllRouteContext) {
  return proxyToGateway("/api/inventory", request, ctx, "DELETE");
}
