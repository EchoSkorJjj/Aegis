import { NextResponse } from "next/server";

import { authedBackendFetch } from "@/lib/server/api";

type RouteParams = { params: Promise<{ id: string }> };

/** Remove one of the caller's passkeys (authenticated). */
export async function DELETE(_request: Request, { params }: RouteParams) {
  const { id } = await params;
  const result = await authedBackendFetch(
    `/auth/mfa/webauthn/credentials/${encodeURIComponent(id)}`,
    { method: "DELETE" },
  );
  if (result.ok) {
    return new NextResponse(null, { status: 204 });
  }
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
