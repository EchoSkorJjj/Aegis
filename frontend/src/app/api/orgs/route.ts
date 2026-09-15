import { NextResponse } from "next/server";

import { authedBackendFetch } from "@/lib/server/api";

/**
 * The caller's organizations (BFF). Proxies GET /orgs with the httpOnly access token attached
 * server-side; drives the workspace switcher.
 */
export async function GET() {
  const result = await authedBackendFetch("/orgs", { method: "GET" });
  return NextResponse.json(result.data ?? [], { status: result.status });
}

/**
 * Creates an organization the caller will own (ORG_ADMIN). The backend gates this on
 * ORG_SIGNUP_ENABLED (403 when off), which we pass straight through.
 */
export async function POST(request: Request) {
  const body = await request.text();
  const result = await authedBackendFetch("/orgs", { method: "POST", body });
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
