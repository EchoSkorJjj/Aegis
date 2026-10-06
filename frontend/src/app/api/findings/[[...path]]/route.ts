import { proxyReadOnlyGet, type CatchAllRouteContext } from "@/lib/server/api";

/** Live org-scoped findings, authenticated through the gateway using the httpOnly session. */
export function GET(request: Request, ctx: CatchAllRouteContext) {
  return proxyReadOnlyGet("/api/findings", request, ctx);
}
