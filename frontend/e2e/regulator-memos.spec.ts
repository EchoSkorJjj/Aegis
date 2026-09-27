import { readFile } from "node:fs/promises";

import { expect, test, type Page } from "@playwright/test";
import type {
  ChangeImpactResponse,
  OwnerRef,
  RegulatorMemo,
  Role,
  ScoreFactor,
} from "../src/lib/types";

// Browser contract checks use a fake BFF. Backend RBAC, tenancy, materiality and the memo's template are
// covered by RegulatorMemoIntegrationTest and MemoTemplateTest.
const SIGNAL = "signal-1";
const SERVICE = "service-1";

// Dates on the page are the reader's locale and time zone; pin both so the assertions are stable.
test.use({ locale: "en-US", timezoneId: "UTC" });

function impact(owners: OwnerRef[], material = true): ChangeImpactResponse {
  const factor = (kind: ScoreFactor["factor"], input: string): ScoreFactor => ({
    factor: kind, input, weight: 1, headroom: 0, limiting: false, setting: null,
  });
  return {
    change: { id: SIGNAL, signalType: "VULNERABILITY", sourceId: "nvd", externalId: "CVE-2021-44228", title: "Log4Shell" },
    matchedComponentCount: 1,
    matchedVendorCount: 0,
    severity: { source: "CVSS", cvssVersion: "3.1", cvssBaseScore: 10, label: null, factor: 1 },
    services: [{
      service: { id: SERVICE, name: "Payments API", criticality: "CRITICAL", exposure: "INTERNET_FACING" },
      score: material ? 100 : 4, tier: material ? "CRITICAL" : "NEGLIGIBLE", material, confidence: "CONFIRMED",
      breakdown: {
        severityFactor: 1, exposure: "INTERNET_FACING", exposureFactor: 1, exposureApplies: true,
        criticality: "CRITICAL", criticalityFactor: 1, confidence: "CONFIRMED", confidenceFactor: 1,
      },
      factors: [factor("SEVERITY", "CVSS 3.1 base 10.0"), factor("EXPOSURE", "INTERNET_FACING"), factor("CRITICALITY", "CRITICAL")],
      explanation: "Worst case.",
      owners,
      evidence: [{
        kind: "ADVISORY_COMPONENT", confidence: "CONFIRMED", explanation: "log4j-core is in range.", affectedProduct: null,
        componentPath: [{ id: "log4j", ecosystem: "go", name: "example.com/log4j", version: "1.0.0" }], vendor: null,
      }],
    }],
    owners: [],
  };
}

function memo(overrides: Partial<RegulatorMemo> = {}): RegulatorMemo {
  return {
    id: "memo-1", changeSignalId: SIGNAL, signalType: "VULNERABILITY", changeTitle: "Log4Shell", kind: "CYBER_THREAT",
    reference: "AEGIS-1A2B3C4D", detectedAt: "2026-09-20T06:15:00Z",
    services: [{
      serviceId: SERVICE, name: "Payments API", criticality: "CRITICAL", exposure: "INTERNET_FACING", score: 100,
      tier: "CRITICAL", material: true,
    }],
    summary: "Log4Shell affects a critical service.", impact: "Remote code execution.", measures: "Upgrading.",
    nextSteps: "Confirm exploitation.", aiDrafted: true, citations: ["C1 NVD description"],
    body: "# Voluntary notification of a significant cyber threat\n\n- Exploited at the entity: [To be confirmed]\n\n<img src=x onerror=alert(1)> stays text\n",
    status: "PROPOSED", createdBy: "analyst-1", createdAt: "2026-09-26T02:00:00Z", decidedBy: null, decidedAt: null,
    dismissReason: null, version: 0,
    ...overrides,
  };
}

type Options = { owners?: OwnerRef[]; memos?: RegulatorMemo[]; material?: boolean };

async function fakeBff(page: Page, role: Role, { owners = [], memos = [], material = true }: Options = {}) {
  const store = [...memos];
  const writes: { path: string; body: unknown }[] = [];
  const empty = { content: [], totalElements: 0, totalPages: 1, number: 0, size: 20 };
  await page.context().addCookies([{ name: "um_refresh", value: "browser-fixture", url: "http://localhost:3000" }]);
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
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
    if (path === "/api/actions/upgrade-recommendations") {
      return route.fulfill({ json: [] });
    }
    if (path === `/api/scoring/change-signals/${SIGNAL}`) {
      return route.fulfill({ json: impact(owners, material) });
    }
    if (path === "/api/users") {
      const content = [{ id: "analyst-1", email: "ana@example.test", displayName: "Ana Analyst", roles: ["ANALYST"], enabled: true }];
      return route.fulfill({ json: { content, totalElements: 1, totalPages: 1, number: 0, size: 100 } });
    }
    if (path === "/api/actions/regulator-memos" && request.method() === "GET") {
      const status = url.searchParams.get("status");
      const signal = url.searchParams.get("changeSignalId");
      const content = store.filter((candidate) => (!status || status.split(",").includes(candidate.status))
        && (!signal || candidate.changeSignalId === signal));
      return route.fulfill({ json: { content, totalElements: content.length, totalPages: 1, number: 0, size: 20 } });
    }
    if (path === "/api/actions/regulator-memos" && request.method() === "POST") {
      writes.push({ path, body: request.postDataJSON() });
      const created = memo({ id: "memo-new", createdBy: "me" });
      store.push(created);
      return route.fulfill({ status: 201, json: created });
    }
    const match = path.match(/^\/api\/actions\/regulator-memos\/([^/]+)(?:\/(approve|dismiss))?$/);
    if (match) {
      const index = store.findIndex((candidate) => candidate.id === match[1]);
      if (index < 0) return route.fulfill({ status: 404, json: { message: "Regulator memo not found" } });
      const decided = { decidedBy: "me", decidedAt: "2026-09-26T09:00:00Z" };
      if (match[2]) writes.push({ path, body: request.postDataJSON() });
      if (match[2] === "approve") store[index] = { ...store[index], ...decided, status: "APPROVED" };
      if (match[2] === "dismiss") {
        store[index] = { ...store[index], ...decided, status: "DISMISSED", dismissReason: request.postDataJSON().reason };
      }
      return route.fulfill({ json: store[index] });
    }
    return route.fulfill({ json: empty });
  });
  return { writes };
}

test("an analyst drafts a DORA memo from the impact page when a service is materially affected", async ({ page }) => {
  const { writes } = await fakeBff(page, "ANALYST");
  await page.goto(`/advisories/${SIGNAL}`);
  const panel = page.getByRole("region", { name: "Regulator notification" });
  await expect(panel).toContainText("1 service is materially affected");
  await expect(panel).toContainText("a voluntary notification of a significant cyber threat (DORA Article 19(2))");

  await panel.getByRole("button", { name: "Draft regulator memo" }).click();
  await expect(page).toHaveURL(/\/responses\/memos\/memo-new$/);
  await expect(page.getByRole("heading", { name: "Significant cyber threat notification" })).toBeVisible();
  expect(writes).toEqual([{ path: "/api/actions/regulator-memos", body: { changeSignalId: SIGNAL } }]);
});

test("a change that affects no service materially offers no memo", async ({ page }) => {
  await fakeBff(page, "ANALYST", { material: false });
  // Wait for the memo lookup itself, so the missing panel is the settled page and not one still loading.
  await Promise.all([
    page.waitForResponse((response) => new URL(response.url()).pathname === "/api/actions/regulator-memos"),
    page.goto(`/advisories/${SIGNAL}`),
  ]);
  await expect(page.getByRole("heading", { name: "Log4Shell" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Draft response" })).toBeVisible();
  await expect(page.getByRole("region", { name: "Regulator notification" })).toHaveCount(0);
});

test("a memo already drafted is linked, not offered again; only an analyst drafts one", async ({ page }) => {
  await fakeBff(page, "ANALYST", { memos: [memo()] });
  await page.goto(`/advisories/${SIGNAL}`);
  const panel = page.getByRole("region", { name: "Regulator notification" });
  await expect(panel.getByRole("link", { name: "View memo →" })).toHaveAttribute("href", "/responses/memos/memo-1");
  await expect(panel).toContainText("AEGIS-1A2B3C4D");
  await expect(panel.getByRole("button")).toHaveCount(0);
});

test("a service owner, even an assigned one, sees that an analyst drafts memos", async ({ page }) => {
  await fakeBff(page, "SERVICE_OWNER", { owners: [{ id: "owner-row", userId: "me", name: "Me" }] });
  await page.goto(`/advisories/${SIGNAL}`);
  const panel = page.getByRole("region", { name: "Regulator notification" });
  await expect(panel).toContainText("An analyst can draft a regulator notification for review.");
  await expect(panel.getByRole("button")).toHaveCount(0);
});

test("approving makes the memo final and downloadable; it is never sent", async ({ page }) => {
  const { writes } = await fakeBff(page, "ANALYST", { memos: [memo()] });
  await page.goto("/responses/memos/memo-1");
  const decision = page.getByRole("region", { name: "Decision" });
  await expect(decision.getByText("Awaiting a decision")).toBeVisible();
  await expect(page.getByText("Drafted by Ana Analyst", { exact: false })).toBeVisible();
  await expect(page.getByRole("region", { name: "Notification" }).locator("pre"))
    .toContainText("<img src=x onerror=alert(1)> stays text");
  await expect(decision.getByRole("button", { name: "Download memo (.md)" })).toHaveCount(0);

  await decision.getByRole("button", { name: "Approve", exact: true }).click();
  await page.getByRole("button", { name: "Make final", exact: true }).click();

  await expect(decision.getByText("Final — ready to submit")).toBeVisible();
  await expect(decision.getByText("Approved by you", { exact: false })).toBeVisible();
  await expect(decision.getByText("Aegis never sends it.", { exact: false })).toBeVisible();
  expect(writes).toEqual([{ path: "/api/actions/regulator-memos/memo-1/approve", body: null }]);

  const [download] = await Promise.all([
    page.waitForEvent("download"),
    decision.getByRole("button", { name: "Download memo (.md)" }).click(),
  ]);
  expect(download.suggestedFilename()).toBe("AEGIS-1A2B3C4D.md");
  expect(await readFile(await download.path(), "utf8")).toBe(memo().body);
});

test("whoever drafted a memo may dismiss it, but another analyst approves it", async ({ page }) => {
  const { writes } = await fakeBff(page, "ANALYST", { memos: [memo({ createdBy: "me" })] });
  await page.goto("/responses/memos/memo-1");
  const decision = page.getByRole("region", { name: "Decision" });
  await expect(decision.getByText("You drafted this, so another analyst approves it.")).toBeVisible();
  await expect(decision.getByRole("button", { name: "Approve", exact: true })).toHaveCount(0);

  await decision.getByRole("button", { name: "Dismiss", exact: true }).click();
  await page.getByLabel("Why is no notification needed?").fill("Not exploitable in our configuration.");
  await page.getByRole("button", { name: "Dismiss memo" }).click();

  await expect(decision.getByText("Dismissed by you", { exact: false })).toBeVisible();
  await expect(decision.getByText("Not exploitable in our configuration.")).toBeVisible();
  expect(writes).toEqual([{
    path: "/api/actions/regulator-memos/memo-1/dismiss", body: { reason: "Not exploitable in our configuration." },
  }]);
});

test("a compliance admin audits memos and a service owner reads them; neither decides", async ({ page }) => {
  await fakeBff(page, "COMPLIANCE_ADMIN", { memos: [memo()] });
  await page.goto("/responses/memos/memo-1");
  const decision = page.getByRole("region", { name: "Decision" });
  await expect(decision.getByText("You audit these decisions rather than make them.", { exact: false })).toBeVisible();
  await expect(decision.getByRole("button")).toHaveCount(0);
});

test("a service owner reads a memo but an analyst decides it", async ({ page }) => {
  await fakeBff(page, "SERVICE_OWNER", { owners: [{ id: "owner-row", userId: "me", name: "Me" }], memos: [memo()] });
  await page.goto("/responses/memos/memo-1");
  const decision = page.getByRole("region", { name: "Decision" });
  await expect(decision.getByText("An analyst approves or dismisses it.")).toBeVisible();
  await expect(decision.getByRole("button")).toHaveCount(0);
});

test("a major incident's timeline says when the initial notification is due", async ({ page }) => {
  await fakeBff(page, "ANALYST", {
    memos: [memo({ kind: "MAJOR_INCIDENT", signalType: "VENDOR_OUTAGE", changeTitle: "Card API outage" })],
  });
  await page.goto("/responses/memos/memo-1");
  await expect(page.getByRole("heading", { name: "Major ICT-related incident notification" })).toBeVisible();
  const timeline = page.getByRole("region", { name: "Timeline" });
  await expect(timeline).toContainText("Detected Sep 20, 2026, 6:15 AM");
  await expect(timeline).toContainText("no later than Sep 21, 2026, 6:15 AM");
});

test("the review queue holds memos awaiting a decision, history holds the final and dismissed ones", async ({ page }) => {
  const approved = memo({
    id: "memo-2", reference: "AEGIS-9F8E7D6C", status: "APPROVED", decidedBy: "analyst-1", decidedAt: "2026-09-26T05:00:00Z",
  });
  await fakeBff(page, "ANALYST", { memos: [memo(), approved] });

  await page.goto("/responses/review");
  const waiting = page.getByRole("region", { name: "Regulator memos awaiting a decision" });
  await expect(waiting.getByRole("link", { name: "Significant cyber threat notification" }))
    .toHaveAttribute("href", "/responses/memos/memo-1");
  await expect(waiting.getByText("AEGIS-9F8E7D6C")).toHaveCount(0);

  await page.goto("/responses/history");
  const decided = page.getByRole("region", { name: "Regulator memos" });
  await expect(decided.getByRole("link", { name: "Significant cyber threat notification" }))
    .toHaveAttribute("href", "/responses/memos/memo-2");
  await expect(decided.getByText("Final", { exact: true })).toBeVisible();
});
