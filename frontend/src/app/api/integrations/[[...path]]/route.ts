import { proxyToGateway, type CatchAllRouteContext } from "@/lib/server/api";

/**
 * BFF proxy for aegis-core's organization integrations: `/api/integrations/github/**` — the connected GitHub
 * App installations, and connecting or disconnecting one. Forwards to the gateway with the caller's httpOnly
 * token; aegis-core scopes everything to the active org and lets only its organization admin change it.
 */
export function GET(request: Request, ctx: CatchAllRouteContext) {
  return proxyToGateway("/api/integrations", request, ctx, "GET");
}

export function POST(request: Request, ctx: CatchAllRouteContext) {
  return proxyToGateway("/api/integrations", request, ctx, "POST");
}

export function DELETE(request: Request, ctx: CatchAllRouteContext) {
  return proxyToGateway("/api/integrations", request, ctx, "DELETE");
}
