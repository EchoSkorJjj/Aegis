import { proxyReadOnlyGet, type CatchAllRouteContext } from "@/lib/server/api";

/**
 * BFF proxy for aegis-core's decision trail (E10): `/api/audit/**` — searching the trail and reading one record with
 * the response as it stood then. GET only: the trail is written by the decisions themselves, never through here.
 * Forwards to the gateway with the caller's httpOnly token; aegis-core reads only their active org's records and
 * only for governance (a 403 stays a 403).
 */
export function GET(request: Request, ctx: CatchAllRouteContext) {
  return proxyReadOnlyGet("/api/audit", request, ctx);
}
