import { expect, test, type Page } from "@playwright/test";
import { fakeBff as fakeQueue } from "./helpers/findings";
import type { ChangeImpactResponse, FindingInterpretation, ScoredService } from "../src/lib/types";

// The AI's reading of each material finding, against a fake BFF: what it says, that it sits beside the score, what a
// finding it hasn't reached yet shows, and the queue's signal. How it's produced is covered by FindingInterpretationsTest
// and FindingInterpretationIntegrationTest.
const CHANGE = { id: "signal-read", signalType: "VULNERABILITY" as const, sourceId: "osv", externalId: "GHSA-read",
  title: "CVE-2023-45133" };

function scored(id: string, name: string, material = true): ScoredService {
  return {
    service: { id, name, criticality: "HIGH", exposure: "INTERNET_FACING" },
    score: material ? 76 : 10, tier: material ? "HIGH" : "LOW", material, confidence: "CONFIRMED",
    breakdown: { severityFactor: 0.95, exposure: "INTERNET_FACING", exposureFactor: 1, exposureApplies: true,
      reachability: "UNKNOWN", reachabilityFactor: 1, reachabilityApplies: true,
      criticality: "HIGH", criticalityFactor: 0.8, confidence: "CONFIRMED", confidenceFactor: 1 },
    factors: [{ factor: "SEVERITY", input: "Feed label", weight: 0.95, headroom: 4, limiting: true, setting: null }],
    reachability: { status: "UNKNOWN", reason: "not read", through: null, usage: null },
    explanation: `Scores for ${name}`, owners: [], evidence: [],
  };
}

const IMPACT: ChangeImpactResponse = {
  change: CHANGE, matchedComponentCount: 1, matchedVendorCount: 0,
  severity: { source: "CVSS", cvssVersion: "3.1", cvssBaseScore: 9, label: null, factor: 0.95 },
  services: [scored("health", "Health Care App"), scored("batch", "Batch worker"), scored("docs", "Docs site", false)],
  owners: [],
};

const READ: FindingInterpretation = {
  serviceId: "health", subject: "@babel/traverse", signal: "UNCERTAIN", confidence: "MEDIUM",
  whatChanged: "Babel's traverse library can run attacker-written code while compiling crafted input.",
  whyItMatters: "It arrives through styled-components, so the risk depends on whether untrusted code is compiled.",
  whatNext: "Mainly a build-time compromise, less likely a runtime one.",
  suggestedAction: "VERIFY", actionDetail: "Check that builds never compile third-party input, then upgrade.",
  condition: "The service compiles input it did not write.", conditionMet: "UNKNOWN",
  assumptions: ["Builds do not compile third-party input."],
  citations: ["Babel's traverse can run arbitrary code during compilation."],
  interpretedAt: "2026-11-09T02:00:00Z",
};

async function fakeChange(page: Page, baseURL: string | undefined, interpretations: () => Response | object) {
  await page.context().addCookies([{ name: "um_refresh", value: "browser-fixture", url: baseURL! }]);
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === "/api/auth/me") return route.fulfill({ json: {
      id: "me", email: "me@example.test", displayName: "Me", roles: ["ANALYST"], orgId: "demo-org", enabled: true,
      emailVerified: true, createdAt: "2026-10-01T00:00:00Z" } });
    if (url.pathname === "/api/orgs") return route.fulfill({ json: [{ id: "demo-org", name: "Demo org", roles: ["ANALYST"],
      status: "ACTIVE" }] });
    if (url.pathname === "/api/scoring/change-signals/signal-read") return route.fulfill({ json: IMPACT });
    if (url.pathname === "/api/change-signals/signal-read") return route.fulfill({ json: { ...CHANGE, summary: "s", url: null,
      occurredAt: "2026-10-13T00:00:00Z", createdAt: null, updatedAt: null, severity: "CRITICAL" } });
    if (url.pathname === "/api/findings/interpretations") {
      expect(url.searchParams.get("changeSignalId")).toBe("signal-read");
      const answer = interpretations();
      return answer instanceof Response ? route.fulfill({ status: answer.status, json: {} }) : route.fulfill({ json: answer });
    }
    return route.fulfill({ json: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } });
  });
}

function card(page: Page, service: string) {
  return page.locator("div.rounded-lg.border").filter({ has: page.getByRole("link", { name: service, exact: true }) });
}

function reading(page: Page, service: string) {
  return card(page, service).getByRole("region", { name: "AI reading" });
}

test("the AI's reading says what changed, why it matters here and what to do, as a suggestion", async ({ page, baseURL }) => {
  await fakeChange(page, baseURL, () => [READ]);
  await page.goto("/advisories/signal-read");

  const read = reading(page, "Health Care App");
  await expect(read.getByText("Uncertain", { exact: true })).toBeVisible();
  await expect(read.getByText("Medium confidence", { exact: true })).toBeVisible();
  await expect(read.getByText(READ.whatChanged)).toBeVisible();
  await expect(read.getByText(READ.whyItMatters)).toBeVisible();
  await expect(read.getByText(READ.whatNext)).toBeVisible();
  await expect(read.getByText("Verify first", { exact: true })).toBeVisible();
  await expect(read.getByText(READ.actionDetail)).toBeVisible();
  await expect(read.getByText(READ.condition!)).toBeVisible();
  await expect(read.getByText("Aegis can't confirm this from what it can see.")).toBeVisible();
  await expect(read.getByText(READ.assumptions[0])).toBeVisible();
  await expect(read).toContainText("never changes the score");

  await read.getByText("What this rests on").click();
  await expect(read.getByText(READ.citations[0])).toBeVisible();
});

test("the condition says when the sources show it holds, and a reading written before it shows none", async ({ page, baseURL }) => {
  await fakeChange(page, baseURL, () => [{ ...READ, conditionMet: "YES" },
    { ...READ, serviceId: "batch", condition: null, conditionMet: "UNKNOWN" }]);
  await page.goto("/advisories/signal-read");

  await expect(reading(page, "Health Care App").getByText("The sources show this holds.")).toBeVisible();
  await expect(reading(page, "Batch worker").getByText("Applies only if")).toHaveCount(0);
});

test("each service card labels its values, so impact and the service's own criticality can't be confused", async ({ page, baseURL }) => {
  await fakeChange(page, baseURL, () => [READ]);
  await page.goto("/advisories/signal-read");

  const header = card(page, "Health Care App").getByRole("group", { name: "Service summary" });
  const value = (label: string) => header.getByRole("definition").filter({
    has: page.locator(`xpath=preceding-sibling::dt[contains(., "${label}")]`) });
  await expect(value("Impact")).toContainText("76.0");
  await expect(value("Impact")).toContainText("High");
  await expect(value("Impact")).not.toContainText("impact");
  await expect(value("Service")).toContainText("Health Care App");
  await expect(value("Business criticality")).toHaveText("High");
  await expect(value("Match")).toHaveText("Confirmed");
  await expect(value("Owners")).toHaveText("No owners assigned");

  // Reached by keyboard too: tabbing onto the Match hint opens its explanation.
  await header.getByRole("button", { name: "What Business criticality means" }).focus();
  await page.keyboard.press("Tab");
  await expect(header.getByRole("button", { name: "What Match means" })).toBeFocused();
  await expect(page.getByText("Whether Aegis has proven this advisory applies")).toBeVisible();
});

test("a finding the AI hasn't reached says so, and a change that isn't material says nothing", async ({ page, baseURL }) => {
  await fakeChange(page, baseURL, () => [READ]);
  await page.goto("/advisories/signal-read");

  await expect(reading(page, "Batch worker")).toContainText("The AI hasn't read this finding yet");
  await expect(reading(page, "Health Care App")).toBeVisible();
  await expect(card(page, "Docs site")).toBeVisible();
  await expect(reading(page, "Docs site")).toHaveCount(0);
});

test("when the readings can't be loaded the rest of the page still works", async ({ page, baseURL }) => {
  await fakeChange(page, baseURL, () => new Response(null, { status: 503 }));
  await page.goto("/advisories/signal-read");

  await expect(card(page, "Health Care App").getByText("Why this score")).toBeVisible();
  await expect(reading(page, "Health Care App")).toHaveCount(0);
});

test("the queue shows the AI's signal and filters on it, keeping the filter in the URL", async ({ page }) => {
  const { listed } = await fakeQueue(page);
  await page.goto("/findings");
  const queue = page.getByRole("region", { name: "Items to respond to" });
  const rows = queue.getByRole("row").filter({ has: page.getByRole("cell") });
  await expect(rows).toHaveCount(20);
  await expect(rows.filter({ hasText: "axios" })).toContainText("AI: Likely real");

  await page.getByLabel("AI signal", { exact: true }).selectOption("LIKELY_REAL");

  await expect(rows).toHaveCount(1);
  expect(listed.at(-1)?.get("signal")).toBe("LIKELY_REAL");
  await expect(page).toHaveURL(/signal=LIKELY_REAL/);

  await page.reload();
  await expect(rows).toHaveCount(1);
  await expect(page.getByLabel("AI signal", { exact: true })).toHaveValue("LIKELY_REAL");
});
