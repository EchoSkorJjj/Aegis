import { expect, test, type Page } from "@playwright/test";
import type { RiskPostureDashboard, Role } from "../src/lib/types";
import { fakeBff, OWNER, OTHER } from "./helpers/findings";

const TODAY = "2026-10-22";
const DAY = 86_400_000;
const DATA: RiskPostureDashboard = {
  asOf: `${TODAY}T09:00:00Z`, from: "2026-09-23", to: TODAY,
  totals: { openItems: 4, criticalItems: 1, highItems: 1, newItems: 2, inReviewItems: 1,
    approvedItems: 1, unassignedItems: 2, affectedServices: 2, totalServices: 3 },
  services: [
    { id: "service-1", name: "Payments API", openItems: 2, criticalItems: 1, highItems: 1, topScore: 100 },
    { id: OTHER.id, name: OTHER.name, openItems: 2, criticalItems: 0, highItems: 0, topScore: 50 },
    { id: "service-3", name: "Ledger", openItems: 0, criticalItems: 0, highItems: 0, topScore: null },
  ],
  owners: [
    { id: OWNER.id, name: OWNER.name, openItems: 2, criticalItems: 1, highItems: 1, topScore: 100 },
    { id: null, name: "Unassigned", openItems: 2, criticalItems: 0, highItems: 0, topScore: 50 },
  ],
  trend: [
    { day: "2026-10-19", capturedAt: "2026-10-19T23:00:00Z", openItems: 7, criticalItems: 2, highItems: 3 },
    { day: "2026-10-21", capturedAt: "2026-10-21T23:00:00Z", openItems: 5, criticalItems: 1, highItems: 2 },
    { day: TODAY, capturedAt: `${TODAY}T09:00:00Z`, openItems: 4, criticalItems: 1, highItems: 1 },
  ],
};

async function dashboard(page: Page, options: { role?: Role; fail?: number; data?: RiskPostureDashboard } = {}) {
  const base = await fakeBff(page, { role: options.role });
  const requests: number[] = [];
  let status = options.fail ?? 200;
  let data = structuredClone(options.data ?? DATA);
  await page.route("**/api/findings/posture?*", async (route) => {
    const days = Number(new URL(route.request().url()).searchParams.get("days"));
    requests.push(days);
    if (status !== 200) return route.fulfill({ status, json: { message: "Unavailable" } });
    const from = new Date(Date.parse(`${TODAY}T00:00:00Z`) - (days - 1) * DAY).toISOString().slice(0, 10);
    return route.fulfill({ json: { ...data, from, trend: data.trend.filter((point) => point.day >= from) } });
  });
  return { ...base, requests, recover: () => { status = 200; }, replace: (next: RiskPostureDashboard) => { data = next; } };
}

test("an analyst sees posture, stage totals, service and owner breakdowns, and drills into open triage", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  const { listed } = await dashboard(page);
  await page.goto("/dashboard");
  await expect(page.getByRole("heading", { name: "Risk posture", exact: true })).toBeVisible();
  await expect(page.getByRole("region", { name: "Open material items", exact: true })).toContainText("4");
  await expect(page.getByRole("region", { name: "Critical and high", exact: true })).toContainText("1 critical · 1 high");
  await expect(page.getByRole("region", { name: "Affected services", exact: true })).toContainText("2 / 3");
  await expect(page.getByRole("region", { name: "Unassigned items", exact: true })).toContainText("2");
  const services = page.getByRole("region", { name: "By service", exact: true });
  await expect(services.getByRole("row").filter({ hasText: "Ledger" })).toContainText("—");
  const owners = page.getByRole("region", { name: "By owner", exact: true });
  await expect(owners).toContainText("owner totals can overlap");
  await expect(owners.getByRole("link", { name: OWNER.name })).toHaveAttribute("href", `/findings?open=true&ownerId=${OWNER.id}`);
  await expect(owners.getByRole("link", { name: "Unassigned", exact: true })).toHaveAttribute("href", "/findings?open=true&unassigned=true");
  await page.screenshot({ path: test.info().outputPath("risk-posture.png"), fullPage: true });
  await services.getByRole("link", { name: OTHER.name }).click();
  await expect(page).toHaveURL(`/findings?open=true&serviceId=${OTHER.id}`);
  await expect(page.getByLabel("Service", { exact: true })).toHaveValue(OTHER.id);
  await expect(page.getByRole("checkbox", { name: "Open items only" })).toBeChecked();
  await expect.poll(() => listed.at(-1)?.get("open")).toBe("true");
  expect(listed.at(-1)?.get("serviceId")).toBe(OTHER.id);
  expect(errors).toEqual([]);
});

test("the trend keeps missing days unknown, exposes daily values, changes period and refreshes", async ({ page }) => {
  const { requests, replace } = await dashboard(page);
  await page.goto("/dashboard");
  const trend = page.getByRole("region", { name: "Open material items trend" });
  await expect(trend).toContainText("3 fewer open items since");
  await expect(trend).toContainText("3 of 30 days recorded");
  await trend.getByText("Daily counts", { exact: true }).click();
  await expect(trend.getByRole("row").filter({ hasText: "2026-10-20" })).toContainText("Not recorded");
  await expect(trend.getByRole("row").filter({ hasText: TODAY })).toContainText("4");
  await page.getByLabel("Trend period").selectOption("7");
  await expect(trend).toContainText("3 of 7 days recorded");
  await page.getByLabel("Trend period").selectOption("90");
  await expect(trend).toContainText("3 of 90 days recorded");
  expect([...new Set(requests)]).toEqual([30, 7, 90]);
  replace({ ...DATA, totals: { ...DATA.totals, openItems: 3 } });
  await page.getByRole("button", { name: "Refresh", exact: true }).click();
  await expect(page.getByRole("region", { name: "Open material items", exact: true })).toContainText("3");
  expect(requests.at(-1)).toBe(90);
});

test("first observation, no open items and an empty estate have honest empty states", async ({ page }) => {
  const empty = structuredClone(DATA);
  empty.totals = { openItems: 0, criticalItems: 0, highItems: 0, newItems: 0, inReviewItems: 0,
    approvedItems: 0, unassignedItems: 0, affectedServices: 0, totalServices: 0 };
  empty.services = [];
  empty.owners = [{ id: null, name: "Unassigned", openItems: 0, criticalItems: 0, highItems: 0, topScore: null }];
  empty.trend = [{ day: TODAY, capturedAt: empty.asOf, openItems: 0, criticalItems: 0, highItems: 0 }];
  const { replace } = await dashboard(page, { data: empty });
  await page.goto("/dashboard");
  await expect(page.getByText("No services registered yet.", { exact: false })).toBeVisible();
  await expect(page.getByText("Trend starts with the first observation.", { exact: false })).toBeVisible();
  await expect(page.getByRole("img", { name: /30-day trend: 1 recorded days/ })).toBeVisible();
  replace({ ...empty, totals: { ...empty.totals, totalServices: 1 } });
  await page.getByRole("button", { name: "Refresh", exact: true }).click();
  await expect(page.getByText("No open material items.", { exact: false })).toBeVisible();
});

test("an unavailable dashboard can be retried and never reports zero risk", async ({ page }) => {
  const { recover } = await dashboard(page, { fail: 503 });
  await page.goto("/dashboard");
  await expect(page.getByRole("alert", { name: "Risk posture error" })).toContainText("Could not load risk posture");
  await expect(page.getByRole("region", { name: "Open material items", exact: true })).toHaveCount(0);
  recover();
  await page.getByRole("button", { name: "Try again" }).click();
  await expect(page.getByRole("region", { name: "Open material items", exact: true })).toContainText("4");
});

test("an organization admin gets a useful landing page without requesting domain data", async ({ page }) => {
  const { requests } = await dashboard(page, { role: "ORG_ADMIN" });
  await page.goto("/dashboard");
  await expect(page.getByRole("link", { name: "Manage organization members" })).toBeVisible();
  expect(requests).toEqual([]);
});

test("risk posture stays usable on a narrow screen", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.setViewportSize({ width: 390, height: 844 });
  await dashboard(page, { role: "COMPLIANCE_ADMIN" });
  await page.goto("/dashboard");
  await expect(page.getByRole("region", { name: "By owner", exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: test.info().outputPath("risk-posture-mobile.png"), fullPage: true });
  expect(errors).toEqual([]);
});

test("switching workspace discards the previous organization's posture and period", async ({ page }) => {
  const { requests, replace } = await dashboard(page);
  let org = "demo-org";
  await page.route("**/api/auth/me", (route) => route.fulfill({ json: {
    id: "me", email: "me@example.test", displayName: "Me", roles: ["ANALYST"], orgId: org,
    enabled: true, emailVerified: true, createdAt: "2026-10-01T00:00:00Z",
  } }));
  await page.route("**/api/orgs", (route) => route.fulfill({ json: [
    { id: "demo-org", name: "Demo org", roles: ["ANALYST"], status: "ACTIVE" },
    { id: "other-org", name: "Other org", roles: ["ANALYST"], status: "ACTIVE" },
  ] }));
  await page.route("**/api/auth/switch-org", (route) => {
    org = route.request().postDataJSON().orgId;
    replace({ ...DATA, totals: { ...DATA.totals, openItems: 0, totalServices: 0 }, services: [], owners: [], trend: [] });
    return route.fulfill({ status: 204 });
  });
  await page.goto("/dashboard");
  await expect(page.getByRole("region", { name: "By service", exact: true })).toContainText("Payments API");
  await page.getByLabel("Trend period").selectOption("7");
  await expect(page.getByLabel("Trend period")).toHaveValue("7");
  await page.getByRole("button", { name: "Demo org Aegis" }).click();
  await page.getByRole("menuitem", { name: "Other org" }).click();
  await expect(page.getByRole("button", { name: "Other org Aegis" })).toBeVisible();
  await expect(page.getByRole("region", { name: "By service", exact: true })).not.toContainText("Payments API");
  await expect(page.getByLabel("Trend period")).toHaveValue("30");
  expect(requests.at(-1)).toBe(30);
});
