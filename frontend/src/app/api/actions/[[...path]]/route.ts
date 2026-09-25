import { proxyToGateway, type CatchAllRouteContext } from "@/lib/server/api";

/**
 * BFF proxy for aegis-core's response actions (E8): `/api/actions/upgrade-proposals/**` — draft,
 * list, read, approve, dismiss. Forwards to the gateway with the caller's httpOnly token; aegis-core
 * scopes proposals to the active org and enforces who may draft and decide (403/409 passed through).
 * Approval is the only call that reaches a repository, and only ever as a draft pull request.
 */
export function GET(request: Request, ctx: CatchAllRouteContext) {
  return proxyToGateway("/api/actions", request, ctx, "GET");
}

export function POST(request: Request, ctx: CatchAllRouteContext) {
  return proxyToGateway("/api/actions", request, ctx, "POST");
}
