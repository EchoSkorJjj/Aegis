import { proxyReadOnlyGet, type CatchAllRouteContext } from "@/lib/server/api";

/**
 * BFF proxy for the read-only aegis-core scoring API (Epic 7). Forwards GET `/api/scoring/**` — one
 * change signal scored per service (`/change-signals/{id}`) or the estate roll-up (`/rollups`) — to
 * the gateway with the caller's httpOnly token; aegis-core scopes the score to the caller's active org
 * and enforces the domain read roles (403 passed through).
 */
export function GET(request: Request, ctx: CatchAllRouteContext) {
  return proxyReadOnlyGet("/api/scoring", request, ctx);
}
