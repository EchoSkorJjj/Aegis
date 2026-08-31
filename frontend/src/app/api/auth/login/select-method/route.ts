import { NextResponse } from "next/server";

import { backendFetch } from "@/lib/server/backend";

/** Login chooser (public): switch the pending challenge to another factor. Proxies the backend. */
export async function POST(request: Request) {
  const body = await request.text();
  const result = await backendFetch("/auth/login/select-method", { method: "POST", body });
  return NextResponse.json(result.data ?? {}, { status: result.status });
}
