import { NextResponse } from "next/server";

import { authedBackendFetch } from "@/lib/server/api";

/**
 * Update the signed-in user's own profile (currently just the display name). The user id is
 * resolved server-side from /users/me — never trusted from the client — then PUT to the backend.
 */
export async function PUT(request: Request) {
  const body = (await request.json().catch(() => null)) as { displayName?: unknown } | null;
  const displayName = typeof body?.displayName === "string" ? body.displayName : null;

  const me = await authedBackendFetch("/users/me");
  if (!me.ok) {
    return NextResponse.json({ message: "Not authenticated" }, { status: 401 });
  }

  const id = (me.data as { id: string }).id;
  const result = await authedBackendFetch(`/users/${id}`, {
    method: "PUT",
    body: JSON.stringify({ displayName }),
  });
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
