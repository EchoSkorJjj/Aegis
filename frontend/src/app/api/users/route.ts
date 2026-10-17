import { NextResponse } from "next/server";

import { authedBackendFetch } from "@/lib/server/api";

/**
 * The active organization's member directory (BFF), read-only. Proxies identity-service `GET /users`
 * with the query string intact (search / role / page / size / sort) and the httpOnly access token
 * attached server-side. Any org member may read it; identity-service scopes it to the active org.
 * Used by pickers such as the service-owner select (`role=SERVICE_OWNER`). Mutations stay on the
 * admin routes.
 */
export async function GET(request: Request) {
  const query = new URL(request.url).search;
  const result = await authedBackendFetch(`/users${query}`, { method: "GET" });
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
