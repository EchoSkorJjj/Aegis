import { expect, test, type Page } from "@playwright/test";
import type { DecisionRecord, RemovedService, Role } from "../src/lib/types";

// Browser contract checks with a fake BFF: the audit log sends each filter to aegis-core and shows what comes back.
// What the trail holds and how each filter matches (memos in every covered service, the half-open period, newest
// first) are DecisionTrailIntegrationTest's and DecisionTrailSearchIntegrationTest's (aegis-core).
const ALICE = { id: "user-alice", email: "alice@example.test", displayName: "Alice Analyst" };
const BOB = { id: "user-bob", email: "bob@example.test", displayName: "Bob Reviewer" };
const PAYMENTS = { id: "service-payments", name: "Payments API" };
const LEDGER = { id: "service-ledger", name: "Ledger" };
// Past the first 100 services the page lists: named by looking it up on its own.
const RENAMED = { id: "service-billing", name: "Billing" };

function record(overrides: Partial<DecisionRecord> = {}): DecisionRecord {
  return {
    id: "record-1", sequence: 101, occurredAt: "2026-10-09T02:00:00Z", subject: "RISK_TICKET", subjectId: "ticket-1",
    responseId: "ticket-1", serviceId: PAYMENTS.id, serviceName: PAYMENTS.name, serviceIds: [PAYMENTS.id],
    changeSignalId: "signal-1", changeTitle: "CVE-2021-44228 in log4j-core 2.14.1", action: "APPROVED",
    actorId: BOB.id, reason: null, score: 91, tier: "CRITICAL", ...overrides,
  };
}

const RECORDS: DecisionRecord[] = [
  // As the trail records a memo: no single service (no id, no name), every one it covers.
  record({ id: "record-3", sequence: 103, action: "DISMISSED", subject: "REGULATOR_MEMO", subjectId: "memo-1",
    responseId: "memo-1", serviceId: null, serviceName: null, serviceIds: [PAYMENTS.id, LEDGER.id], actorId: BOB.id,
    reason: "Not a significant threat", occurredAt: "2026-10-10T03:00:00Z" }),
  record({ id: "record-2", sequence: 102, action: "OVERRIDDEN", actorId: BOB.id, reason: "Behind the WAF" }),
  record({ id: "record-1", sequence: 101, action: "DRAFTED", actorId: ALICE.id, occurredAt: "2026-10-08T01:00:00Z" }),
  record({ id: "record-0", sequence: 100, action: "DRAFTED", actorId: "user-gone", serviceId: LEDGER.id,
    serviceName: LEDGER.name, serviceIds: [LEDGER.id], score: null, tier: null }),
];

async function fakeBff(
  page: Page,
  role: Role,
  records: DecisionRecord[] = RECORDS,
  removed: RemovedService[] = [],
) {
  const searches: URLSearchParams[] = [];
  const lookups: string[] = [];
  const memberPages: URLSearchParams[] = [];
  const serviceSearches: (string | null)[] = [];
  await page.context().addCookies([{ name: "um_refresh", value: "browser-fixture", url: "http://localhost:3000" }]);
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    if (path === "/api/auth/me") {
      return route.fulfill({ json: {
        id: "me", email: "me@example.test", displayName: "Me", roles: [role], orgId: "demo-org",
        enabled: true, emailVerified: true, createdAt: "2026-09-01T00:00:00Z",
      } });
    }
    if (path === "/api/orgs") {
      return route.fulfill({ json: [{ id: "demo-org", name: "Demo org", roles: [role], status: "ACTIVE" }] });
    }
    if (path === "/api/users") {
      memberPages.push(url.searchParams);
      const members = [ALICE, BOB].map((member) => ({ ...member, roles: ["ANALYST"], enabled: true }));
      return route.fulfill({ json: { content: members, totalElements: 2, totalPages: 1, number: 0, size: 500 } });
    }
    if (path === "/api/inventory/services") {
      const search = url.searchParams.get("search");
      serviceSearches.push(search);
      const found = [LEDGER, PAYMENTS].filter((item) => !search || item.name.toLowerCase().includes(search.toLowerCase()));
      return route.fulfill({ json: { content: found, totalElements: found.length, totalPages: 1, number: 0, size: 50 } });
    }
    if (path === "/api/audit/decisions/removed-services") {
      return route.fulfill({ json: removed });
    }
    const service = path.match(/^\/api\/inventory\/services\/([^/]+)$/);
    if (service) {
      lookups.push(service[1]);
      const found = [LEDGER, PAYMENTS, RENAMED].find((item) => item.id === service[1]);
      return found
        ? route.fulfill({ json: found })
        : route.fulfill({ status: 404, json: { message: `Business service not found: ${service[1]}` } });
    }
    if (path === "/api/audit/decisions") {
      if (role === "SERVICE_OWNER") return route.fulfill({ status: 403, json: { message: "Forbidden" } });
      searches.push(url.searchParams);
      const actorId = url.searchParams.get("actorId");
      const action = url.searchParams.get("action");
      const serviceId = url.searchParams.get("serviceId");
      const matching = records.filter((item) =>
        (!actorId || item.actorId === actorId) &&
        (!action || action.split(",").includes(item.action)) &&
        (!serviceId || item.serviceIds.includes(serviceId)));
      const ordered = url.searchParams.get("sort") === "sequence,desc" ? matching : [...matching].reverse();
      return route.fulfill({ json: { content: ordered, totalElements: ordered.length, totalPages: 1, number: 0, size: 25 } });
    }
    const one = path.match(/^\/api\/audit\/decisions\/([^/]+)$/);
    if (one) {
      const found = records.find((item) => item.id === one[1]);
      return route.fulfill({ json: { ...found, state: { status: "DISMISSED", body: "The memo as it stood." } } });
    }
    return route.fulfill({ json: { content: [], totalElements: 0, totalPages: 1, number: 0, size: 20 } });
  });
  return { searches, lookups, memberPages, serviceSearches };
}

test("a compliance admin searches the trail by person, decision and service, newest first", async ({ page }) => {
  const { searches, memberPages } = await fakeBff(page, "COMPLIANCE_ADMIN");
  await page.goto("/reports/audit");
  const log = page.getByRole("region", { name: "Decision records" });
  const rows = log.getByRole("row").filter({ has: page.getByRole("cell") });
  await expect(rows).toHaveCount(4);
  await expect(log).toContainText("4 decision records in total");
  await expect(page.getByRole("link", { name: "Audit log" })).toHaveAttribute("href", "/reports/audit");
  expect(searches.at(-1)?.get("sort")).toBe("sequence,desc");
  // Everyone is named: the member directory is read page by page, not one page of it.
  await expect.poll(() => memberPages.length).toBeGreaterThan(0);
  expect(memberPages.at(0)?.get("size")).toBe("500");
  expect(memberPages.at(0)?.get("sort")).toBe("id,asc");

  // Newest first: the memo, covering two services, dismissed with why.
  const memo = rows.first();
  await expect(memo).toContainText("Dismissed");
  await expect(memo).toContainText("Regulator memo");
  await expect(memo).toContainText("Bob Reviewer");
  // A memo names no single service: its first is named from the inventory, the rest counted.
  await expect(memo.getByRole("button", { name: PAYMENTS.name })).toBeVisible();
  await expect(memo).toContainText("and 1 other service");
  await expect(memo).not.toContainText("removed");
  await expect(memo).toContainText("Why: Not a significant threat");
  // Someone the directory no longer lists is still shown, by id; a record without a score shows none.
  await expect(rows.last()).toContainText("Former member (user-gon)");

  await page.getByLabel("Decided by", { exact: true }).selectOption(BOB.id);
  await expect(rows).toHaveCount(2);
  expect(searches.at(-1)?.get("actorId")).toBe(BOB.id);
  await expect(log).toContainText("2 decision records match");

  await page.getByLabel("Decision", { exact: true }).selectOption("OVERRIDDEN");
  await expect(rows).toHaveCount(1);
  expect(searches.at(-1)?.get("action")).toBe("OVERRIDDEN");
  await expect(rows.first()).toContainText("Why: Behind the WAF");
  await expect(log).toContainText("1 decision record matches");

  await page.getByRole("button", { name: "Clear", exact: true }).click();
  await expect(rows).toHaveCount(4);
  expect(searches.at(-1)?.get("actorId")).toBeNull();

  // A row's service or person narrows the search to it.
  await rows.last().getByRole("button", { name: LEDGER.name }).click();
  await expect(page.getByLabel("Service", { exact: true })).toHaveValue(LEDGER.id);
  await expect(rows).toHaveCount(2);
  expect(searches.at(-1)?.get("serviceId")).toBe(LEDGER.id);

  await page.getByLabel("Order", { exact: true }).selectOption("oldest");
  await expect(rows.first()).toContainText("Former member");
  expect(searches.at(-1)?.get("sort")).toBeNull();
  await page.screenshot({ path: test.info().outputPath("audit-log.png"), fullPage: true });
});

test("a record opens to the response as it stood then", async ({ page }) => {
  await fakeBff(page, "ANALYST");
  await page.goto("/reports/audit");
  const rows = page.getByRole("region", { name: "Decision records" }).getByRole("row").filter({ has: page.getByRole("cell") });
  await rows.first().getByRole("button", { name: "View", exact: true }).click();

  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("heading", { name: "Dismissed · Regulator memo" })).toBeVisible();
  await expect(dialog).toContainText("Record #103");
  await expect(dialog).toContainText("by Bob Reviewer");
  await expect(dialog).toContainText("The memo as it stood.");
  await expect(dialog).toContainText(`Services${PAYMENTS.name}, ${LEDGER.name}`);
  await expect(dialog).toContainText("A regulator memo covers every service the change affects");
  await expect(dialog.getByRole("link", { name: "Open the response as it is now" })).toHaveAttribute("href", "/responses/memos/memo-1");
  await expect(dialog.getByRole("link", { name: "CVE-2021-44228 in log4j-core 2.14.1" })).toHaveAttribute("href", "/findings/signal-1");
});

test("a memo's services are named from the inventory, or as the trail named them once removed", async ({ page }) => {
  const memo = (id: string, sequence: number, serviceIds: string[]) => record({ id, sequence,
    subject: "REGULATOR_MEMO", subjectId: id, responseId: id, serviceId: null, serviceName: null, serviceIds });
  const { lookups, searches, serviceSearches } = await fakeBff(page, "COMPLIANCE_ADMIN", [
    memo("memo-a", 202, [RENAMED.id]),
    memo("memo-b", 201, ["service-gone", PAYMENTS.id]),
    memo("memo-c", 200, ["service-unnamed"]),
  ], [{ id: "service-gone", name: "Old billing" }, { id: "service-unnamed", name: null }]);
  await page.goto("/reports/audit");
  const rows = page.getByRole("region", { name: "Decision records" }).getByRole("row").filter({ has: page.getByRole("cell") });

  // From the inventory, however many services it has; as their records named them once removed; else said so.
  await expect(rows.nth(0).getByRole("button", { name: RENAMED.name })).toBeVisible();
  await expect(rows.nth(1).getByRole("button", { name: "Old billing" })).toBeVisible();
  await expect(rows.nth(1)).toContainText("and 1 other service");
  await expect(rows.nth(2).getByRole("button", { name: "A removed service" })).toBeVisible();
  // Only what the removed list didn't name is looked up, each once.
  expect([...lookups].sort()).toEqual([PAYMENTS.id, RENAMED.id].sort());

  // The service filter searches the inventory on the server, and offers the removed services too.
  const picker = page.getByLabel("Service", { exact: true });
  await expect(picker.locator("optgroup[label='No longer in the inventory'] option")).toHaveText([
    "Old billing (removed)", "A service (service-) (removed)",
  ]);
  await page.getByLabel("Search services by name").fill("led");
  await expect(picker.locator("option", { hasText: LEDGER.name })).toHaveCount(1);
  await expect(picker.locator("option", { hasText: PAYMENTS.name })).toHaveCount(0);
  expect(serviceSearches).toContain("led");
  await picker.selectOption({ label: "Old billing (removed)" });
  await expect(rows).toHaveCount(1);
  expect(searches.at(-1)?.get("serviceId")).toBe("service-gone");
});

test("a service owner has no audit log", async ({ page }) => {
  await fakeBff(page, "SERVICE_OWNER");
  await page.goto("/reports/audit");
  await expect(page.getByText("You don't have access to the audit log", { exact: false })).toBeVisible();
  // Both reports are governance's, so the owner has no Reports group at all.
  await expect(page.getByRole("button", { name: "Findings" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Reports" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Audit log" })).toHaveCount(0);
});
