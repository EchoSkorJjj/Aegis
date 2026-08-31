import { NextResponse } from "next/server";

import { authedBackendFetch } from "@/lib/server/api";

type RouteParams = { params: Promise<{ id: string }> };

/**
 * Admin user mutations (BFF). Proxies to the backend with the admin's httpOnly access token;
 * the backend enforces the ADMIN role (403 passed through).
 *   PUT    → update a user (e.g. { enabled })
 *   DELETE → remove a user
 */
export async function PUT(request: Request, { params }: RouteParams) {
  const { id } = await params;
  const body = await request.text();
  const result = await authedBackendFetch(`/users/${id}`, { method: "PUT", body });
  return NextResponse.json(result.data ?? {}, { status: result.status });
}

export async function DELETE(_request: Request, { params }: RouteParams) {
  const { id } = await params;
  const result = await authedBackendFetch(`/users/${id}`, { method: "DELETE" });
  if (result.ok) {
    return new NextResponse(null, { status: 204 });
  }
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
