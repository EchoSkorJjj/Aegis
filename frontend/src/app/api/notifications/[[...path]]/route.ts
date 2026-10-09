import { proxyToGateway, type CatchAllRouteContext } from "@/lib/server/api";

/**
 * BFF proxy for aegis-core's owner notifications (E9 story 5): `/api/notifications/**` — your notifications, how
 * many are unread, and marking them read. Forwards to the gateway with the caller's httpOnly token; aegis-core
 * returns only the caller's own, in their active org.
 */
export function GET(request: Request, ctx: CatchAllRouteContext) {
  return proxyToGateway("/api/notifications", request, ctx, "GET");
}

export function POST(request: Request, ctx: CatchAllRouteContext) {
  return proxyToGateway("/api/notifications", request, ctx, "POST");
}
