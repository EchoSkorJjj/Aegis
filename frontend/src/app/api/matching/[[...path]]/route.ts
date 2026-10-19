import { proxyReadOnlyGet, type CatchAllRouteContext } from "@/lib/server/api";

/**
 * BFF proxy for the read-only aegis-core matching API (Epic 6). Forwards
 * GET `/api/matching/change-signals/{id}` to the gateway with the caller's httpOnly token; aegis-core
 * scopes the match to the caller's active org and enforces the domain read roles (403 passed through).
 */
export function GET(request: Request, ctx: CatchAllRouteContext) {
  return proxyReadOnlyGet("/api/matching", request, ctx);
}
