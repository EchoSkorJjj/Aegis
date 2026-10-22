import { expect, test, type Page } from "@playwright/test";
import type { ChangeImpactResponse, ReachabilityAssessmentItem, Role, ScoredService } from "../src/lib/types";

// The AI's suggestion on whether each service's code calls the vulnerable function (E11-5), against a fake BFF: kept
// answers with the lines they cite, asking for one, why an item isn't assessed, and who may ask. How it's worked out
// is covered by ReachabilityAssessmentIntegrationTest.
const REVISION = "0123456789abcdef0123456789abcdef01234567";
const CHANGE = { id: "signal-ai", signalType: "VULNERABILITY" as const, sourceId: "osv", externalId: "GHSA-ai", title: "CVE-2026-8888" };

function scored(id: string, name: string): ScoredService {
  return {
    service: { id, name, criticality: "CRITICAL", exposure: "INTERNET_FACING" },
    score: 90, tier: "CRITICAL", material: true, confidence: "CONFIRMED",
    breakdown: { severityFactor: 0.9, exposure: "INTERNET_FACING", exposureFactor: 1, exposureApplies: true,
      reachability: "IMPORTED", reachabilityFactor: 1, reachabilityApplies: true,
      criticality: "CRITICAL", criticalityFactor: 1, confidence: "CONFIRMED", confidenceFactor: 1 },
    factors: [{ factor: "SEVERITY", input: "CVSS 3.1 base 9", weight: 0.9, headroom: 10, limiting: true, setting: null }],
    reachability: { status: "IMPORTED", reason: null, through: null,
      usage: { status: "IMPORTED", reason: null, importedAs: ["lodash"], siteCount: 1, sites: [] } },
    explanation: `CVE-2026-8888 scores 90.0 for ${name}`, owners: [{ id: `owner-${id}`, userId: "owner-me", name: "Olive Owner" }],
    evidence: [],
  };
}

const IMPACT: ChangeImpactResponse = {
  change: CHANGE, matchedComponentCount: 1, matchedVendorCount: 0,
  severity: { source: "CVSS", cvssVersion: "3.1", cvssBaseScore: 9, label: null, factor: 0.9 },
  services: [scored("storefront", "Storefront"), scored("dashboard", "Dashboard"), scored("docs", "Docs site")],
  owners: [],
};

const ASSESSED: ReachabilityAssessmentItem = {
  serviceId: "storefront", ecosystem: "npm", packageName: "lodash", status: "ASSESSED", verdict: "NOT_CALLED",
  rationale: "The code takes only merge from lodash; the advisory concerns template.",
  citedLines: [{ path: "src/app.ts", startLine: 1, endLine: 11, line: 6,
    url: `https://github.com/acme/storefront/blob/${REVISION}/src/app.ts#L6` }],
  checks: [], searches: [{ term: "template", lines: 0, files: 0 }],
  assessedAt: "2026-10-16T02:00:00Z", reason: null, assessable: true,
};
const UNASKED: ReachabilityAssessmentItem = {
  serviceId: "dashboard", ecosystem: "npm", packageName: "lodash", status: "NOT_ASSESSED", verdict: null,
  rationale: null, citedLines: [], checks: [], searches: [], assessedAt: null, reason: "Nobody has asked the AI yet",
  assessable: true,
};
const INDIRECT: ReachabilityAssessmentItem = {
  serviceId: "docs", ecosystem: "npm", packageName: "lodash", status: "NOT_ASSESSED", verdict: null,
  rationale: null, citedLines: [], checks: [], searches: [], assessedAt: null, assessable: false,
  reason: "lodash isn't imported itself, but it comes in through next, which the code imports",
};
const CALLED: ReachabilityAssessmentItem = {
  ...UNASKED, status: "ASSESSED", verdict: "CALLED", reason: null, assessedAt: "2026-10-16T02:05:00Z",
  rationale: "The excerpt passes request input to template.",
  citedLines: [{ path: "lib/render.ts", startLine: 10, endLine: 20, line: 15,
    url: `https://github.com/acme/dashboard/blob/${REVISION}/lib/render.ts#L15` }],
  checks: ["Whether the view name can come from a request", "Whether lodash is below 4.17.21 in production"],
  searches: [{ term: "template", lines: 2, files: 1 }, { term: "templateSettings", lines: 0, files: 0 }],
};

async function fakeBff(page: Page, role: Role, { failing = false }: { failing?: boolean } = {}) {
  const asked: unknown[] = [];
  await page.context().addCookies([{ name: "um_refresh", value: "browser-fixture", url: "http://localhost:3100" }]);
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === "/api/auth/me") return route.fulfill({ json: {
      id: "owner-me", email: "me@example.test", displayName: "Me", roles: [role], orgId: "demo-org", enabled: true,
      emailVerified: true, createdAt: "2026-10-01T00:00:00Z",
    } });
    if (url.pathname === "/api/orgs") return route.fulfill({ json: [{ id: "demo-org", name: "Demo org", roles: [role], status: "ACTIVE" }] });
    if (url.pathname === "/api/scoring/change-signals/signal-ai") return route.fulfill({ json: IMPACT });
    if (url.pathname === "/api/change-signals/signal-ai") return route.fulfill({ json: {
      ...CHANGE, summary: "lodash before 4.17.21: command injection through template.", url: null,
      occurredAt: "2026-10-13T00:00:00Z", createdAt: null, updatedAt: null, severity: "CRITICAL",
    } });
    if (url.pathname === "/api/actions/reachability-assessments" && request.method() === "POST") {
      asked.push(request.postDataJSON());
      await new Promise((resolve) => setTimeout(resolve, 300)); // reading the code takes a moment
      return route.fulfill({ json: [failing
        ? { ...UNASKED, reason: "The AI service is unavailable; try again later" }
        : CALLED] });
    }
    if (url.pathname === "/api/actions/reachability-assessments") {
      expect(url.searchParams.get("changeSignalId")).toBe("signal-ai");
      return route.fulfill({ json: [ASSESSED, UNASKED, INDIRECT] });
    }
    return route.fulfill({ json: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } });
  });
  return { asked };
}

/** The service's card on the change-impact page. */
function card(page: Page, service: string) {
  return page.locator("div.rounded-lg.border").filter({ has: page.getByRole("link", { name: service, exact: true }) });
}

function suggestion(page: Page, service: string) {
  return card(page, service).getByRole("region", { name: "AI suggestion" });
}

test("a kept answer shows what the AI suggests, why, and the lines it rests on — beside the score, not in it", async ({ page }) => {
  await fakeBff(page, "ANALYST");
  await page.goto("/advisories/signal-ai");

  const storefront = suggestion(page, "Storefront");
  await expect(storefront.getByText("Doesn't use it", { exact: true })).toBeVisible();
  await expect(storefront.getByText("AI suggestion: does the code reach the vulnerable part?")).toBeVisible();
  await expect(storefront.getByText("The code takes only merge from lodash; the advisory concerns template.")).toBeVisible();
  await expect(storefront.getByRole("link", { name: "src/app.ts:1–11" }))
    .toHaveAttribute("href", `https://github.com/acme/storefront/blob/${REVISION}/src/app.ts#L6`);
  // A complete search that found no mention of the vulnerable function is part of why.
  await expect(storefront.getByText("Searched the code for template (no match).")).toBeVisible();
  await expect(storefront.getByRole("list", { name: "To check" })).toHaveCount(0);
  await expect(storefront.getByText("A suggestion from the AI: it doesn't change the score.")).toBeVisible();
  await expect(storefront.getByRole("button", { name: "Ask again" })).toBeVisible();
  await page.screenshot({ path: test.info().outputPath("ai-suggestion.png"), fullPage: true });
});

test("asking the AI reads the code, then shows its answer in place", async ({ page }) => {
  const { asked } = await fakeBff(page, "ANALYST");
  await page.goto("/findings/signal-ai");

  const dashboard = suggestion(page, "Dashboard");
  await expect(dashboard.getByText("Not assessed: Nobody has asked the AI yet")).toBeVisible();
  await dashboard.getByRole("button", { name: "Ask the AI" }).click();
  await expect(dashboard.getByText("Reading the code and asking the AI…")).toBeVisible();
  await expect(dashboard.getByText("Uses it", { exact: true })).toBeVisible();
  await expect(dashboard.getByRole("link", { name: "lib/render.ts:10–20" })).toBeVisible();
  await expect(dashboard.getByText("Searched the code for template (2 lines in 1 file), templateSettings (no match)."))
    .toBeVisible();
  await expect(dashboard.getByRole("list", { name: "To check" }).getByRole("listitem")).toHaveText([
    "Whether the view name can come from a request", "Whether lodash is below 4.17.21 in production"]);
  expect(asked).toEqual([{ serviceId: "dashboard", changeSignalId: "signal-ai" }]);
});

test("an item the AI can't answer says why, in place", async ({ page }) => {
  await fakeBff(page, "ANALYST", { failing: true });
  await page.goto("/advisories/signal-ai");

  const dashboard = suggestion(page, "Dashboard");
  await dashboard.getByRole("button", { name: "Ask the AI" }).click();
  await expect(dashboard.getByText("Not assessed: The AI service is unavailable; try again later")).toBeVisible();
  await expect(dashboard.getByRole("button", { name: "Ask the AI" })).toBeVisible();

  // A package the code doesn't import itself is never sent: nothing to ask.
  const docs = suggestion(page, "Docs site");
  await expect(docs.getByText("Not assessed: lodash isn't imported itself, but it comes in through next, which the code imports")).toBeVisible();
  await expect(docs.getByRole("button")).toHaveCount(0);
});

test("a compliance admin reads the answers but can't ask", async ({ page }) => {
  await fakeBff(page, "COMPLIANCE_ADMIN");
  await page.goto("/advisories/signal-ai");

  await expect(suggestion(page, "Storefront").getByText("Doesn't use it", { exact: true })).toBeVisible();
  await expect(suggestion(page, "Dashboard").getByText("Not assessed: Nobody has asked the AI yet")).toBeVisible();
  await expect(page.getByRole("button", { name: /Ask (the AI|again)/ })).toHaveCount(0);
});
