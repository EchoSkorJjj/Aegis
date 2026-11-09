import { expect, test, type Page } from "@playwright/test";
import type { BusinessService, SoftwareComponent } from "../src/lib/types";

// The "Link an existing component" picker, against a fake BFF: what it says when there are more options than it lists,
// and when there's nothing left to link. What counts as already part of the service is covered by
// InventoryApiIntegrationTest.
const SERVICE: BusinessService = {
  id: "svc", name: "Picker demo", criticality: "HIGH", exposure: "INTERNAL", repositoryUrl: null, repositoryBranch: null,
  sbomImportedAt: null, sbomImportSource: null, importScan: null,
  createdAt: "2026-09-24T00:00:00Z", updatedAt: "2026-09-24T00:00:00Z",
};

function component(index: number): SoftwareComponent {
  return { id: `c-${index}`, ecosystem: "npm", name: `lib-${index}`, version: "1.0.0",
    createdAt: "2026-09-24T00:00:00Z", updatedAt: "2026-09-24T00:00:00Z" } as SoftwareComponent;
}

const EMPTY = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 };

async function fakeBff(page: Page, baseURL: string | undefined, options: (term: string) => { rows: number; total: number }) {
  const searches: URLSearchParams[] = [];
  await page.context().addCookies([{ name: "um_refresh", value: "browser-fixture", url: baseURL! }]);
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === "/api/auth/me") return route.fulfill({ json: {
      id: "me", email: "me@example.test", displayName: "Me", roles: ["ANALYST"], orgId: "demo-org", enabled: true,
      emailVerified: true, createdAt: SERVICE.createdAt } });
    if (url.pathname === "/api/orgs") return route.fulfill({ json: [{ id: "demo-org", name: "Demo org", roles: ["ANALYST"],
      status: "ACTIVE" }] });
    if (url.pathname === "/api/actions/upgrade-recommendations") return route.fulfill({ json: [] });
    if (url.pathname === "/api/inventory/services/svc") return route.fulfill({ json: SERVICE });
    if (url.pathname === "/api/inventory/components") {
      searches.push(url.searchParams);
      const { rows, total } = options(url.searchParams.get("search") ?? "");
      return route.fulfill({ json: { ...EMPTY, content: Array.from({ length: rows }, (_, index) => component(index)),
        totalElements: total, totalPages: 1 } });
    }
    return route.fulfill({ json: EMPTY });
  });
  return { searches };
}

function picker(page: Page) {
  return page.getByRole("combobox", { name: "Link an existing component…" });
}

test("with more options than it lists, the picker says so and how to narrow them", async ({ page, baseURL }) => {
  const { searches } = await fakeBff(page, baseURL, () => ({ rows: 20, total: 1173 }));
  await page.goto("/inventory/services/svc");

  await picker(page).click();

  await expect(page.getByRole("option")).toHaveCount(20);
  await expect(page.getByText("Showing the first 20 of 1,173. Type to narrow the list.")).toBeVisible();
  expect(searches.at(-1)?.get("excludeLinkedToService")).toBe("svc");
  expect(searches.at(-1)?.get("size")).toBe("20");
});

test("when every option fits, the picker adds no note", async ({ page, baseURL }) => {
  await fakeBff(page, baseURL, () => ({ rows: 3, total: 3 }));
  await page.goto("/inventory/services/svc");

  await picker(page).click();

  await expect(page.getByRole("option")).toHaveCount(3);
  await expect(page.getByText(/Showing the first/)).toHaveCount(0);
});

test("with nothing left to link, the picker says why rather than asking for a search", async ({ page, baseURL }) => {
  await fakeBff(page, baseURL, () => ({ rows: 0, total: 0 }));
  await page.goto("/inventory/services/svc");

  await picker(page).click();
  await expect(page.getByRole("status").filter({ hasText: "Nothing to link" }))
    .toContainText("every component in the catalogue is already part of this service");

  await picker(page).fill("left-pad");
  await expect(page.getByRole("status").filter({ hasText: "No matches." })).toBeVisible();
});
