import { proxyToGateway, type CatchAllRouteContext } from "@/lib/server/api";

/**
 * BFF proxy for aegis-core's organization integrations: `/api/integrations/github/**` (the connected GitHub
 * App installations) and `/api/integrations/jira/**` (the Jira site risk tickets are filed in, and its project
 * settings). Forwards to the gateway with the caller's httpOnly token; aegis-core scopes everything to the
 * active org and lets only its organization admin change it.
 */
export function GET(request: Request, ctx: CatchAllRouteContext) {
  return proxyToGateway("/api/integrations", request, ctx, "GET");
}

export function POST(request: Request, ctx: CatchAllRouteContext) {
  return proxyToGateway("/api/integrations", request, ctx, "POST");
}

export function PUT(request: Request, ctx: CatchAllRouteContext) {
  return proxyToGateway("/api/integrations", request, ctx, "PUT");
}

export function DELETE(request: Request, ctx: CatchAllRouteContext) {
  return proxyToGateway("/api/integrations", request, ctx, "DELETE");
}
