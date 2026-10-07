import { expect, test } from "@playwright/test";

// The BFF's catch-all proxies forward only inside their own prefix on the gateway. The router decodes
// an encoded slash (or backslash) inside a segment, and `fetch` would resolve the `../` that leaves, so
// such paths are refused before any backend call — no stack needed.
const ESCAPES = [
  "/api/actions/..%2Finventory%2Fservices",
  "/api/inventory/..%2F..%2Fusers",
  "/api/scoring/x%2F..%2F..%2F..%2Factuator%2Fmetrics",
  "/api/matching/..%5C..%5Cusers",
  "/api/change-signals/..%2F..%2Fusers",
  "/api/findings/..%2Finventory%2Fservices",
];

for (const path of ESCAPES) {
  test(`the BFF refuses to forward ${path}`, async ({ request }) => {
    const response = await request.get(path);
    expect(response.status()).toBe(400);
    expect(await response.json()).toEqual({ message: "Invalid path" });
  });
}

test("a path inside the prefix is forwarded as the signed-in user", async ({ request }) => {
  const response = await request.get("/api/actions/upgrade-proposals?status=PROPOSED,FAILED");
  expect(response.status()).toBe(401);
  expect(await response.json()).toEqual({ message: "Not authenticated" });
});
