import { proxyReadOnlyGet, type CatchAllRouteContext } from "@/lib/server/api";

/**
 * BFF proxy for the read-only aegis-core change-signals feed (unified advisories / vendor outages /
 * security news — global reference data). Forwards GET `/api/change-signals` and
 * `/api/change-signals/{id}` to the gateway with the caller's httpOnly token; the backend enforces
 * the domain read roles (403 passed through).
 */
export function GET(request: Request, ctx: CatchAllRouteContext) {
  return proxyReadOnlyGet("/api/change-signals", request, ctx);
}
