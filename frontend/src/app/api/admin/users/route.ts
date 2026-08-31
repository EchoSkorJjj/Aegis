import { NextResponse } from "next/server";

import { authedBackendFetch } from "@/lib/server/api";

/**
 * Admin users listing (BFF). Proxies GET /users with the caller's query string intact
 * (search / from / to / page / size / sort) and the httpOnly access token attached
 * server-side. Authorization is the backend's job: it returns 403 for non-admins, which
 * we pass straight through.
 */
export async function GET(request: Request) {
  const query = new URL(request.url).search;
  const result = await authedBackendFetch(`/users${query}`, { method: "GET" });
  return NextResponse.json(result.data ?? {}, { status: result.status });
}

/** Admin-provisions an account (name + email); the backend emails an invite. Backend enforces ADMIN. */
export async function POST(request: Request) {
  const body = await request.text();
  const result = await authedBackendFetch("/users", { method: "POST", body });
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
