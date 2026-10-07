import { proxyReadOnlyGet, type CatchAllRouteContext } from "@/lib/server/api";

/** The triage queue (read-only, org-scoped), authenticated through the gateway using the httpOnly session. */
export function GET(request: Request, ctx: CatchAllRouteContext) {
  return proxyReadOnlyGet("/api/findings", request, ctx);
}
