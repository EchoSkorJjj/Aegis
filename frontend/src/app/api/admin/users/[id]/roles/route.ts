import { NextResponse } from "next/server";

import { authedBackendFetch } from "@/lib/server/api";

type RouteParams = { params: Promise<{ id: string }> };

/** Admin: replace a user's roles (BFF). Proxies to PUT /users/{id}/roles; backend enforces ADMIN. */
export async function PUT(request: Request, { params }: RouteParams) {
  const { id } = await params;
  const body = await request.text();
  const result = await authedBackendFetch(`/users/${id}/roles`, { method: "PUT", body });
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
