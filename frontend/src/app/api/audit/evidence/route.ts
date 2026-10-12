import { NextResponse } from "next/server";

import { authedBackendDownload } from "@/lib/server/api";

/** What the browser needs from the export's response: the file's type and name, and what it holds. */
const PASSED_ON = ["content-type", "content-disposition", "x-evidence-csv-sha256", "x-evidence-records"];

/**
 * BFF route for aegis-core's evidence export (E10 story 3): `/api/audit/evidence?serviceId=&from=&to=&format=&zone=`,
 * a service's decision trail for a period as a PDF or CSV. Its bytes pass through untouched (the catch-all
 * `/api/audit/**` proxy reads JSON only); an error stays the backend's JSON error with its status.
 */
export async function GET(request: Request) {
  const query = new URL(request.url).search;
  const result = await authedBackendDownload(`/api/audit/evidence${query}`);
  if (!result.ok || !result.body || !result.headers) {
    return NextResponse.json(result.data ?? {}, { status: result.status });
  }
  const headers = new Headers({ "Cache-Control": "no-store" });
  for (const name of PASSED_ON) {
    const value = result.headers.get(name);
    if (value) headers.set(name, value);
  }
  return new NextResponse(result.body, { status: result.status, headers });
}
