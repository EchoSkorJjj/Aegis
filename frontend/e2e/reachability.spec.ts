import { expect, test, type Page } from "@playwright/test";
import type { ChangeImpactResponse, Reachability, ScoredService, ScoreFactor } from "../src/lib/types";

// The score's reachability factor (E11-4) against a fake BFF: each service's breakdown names it with the files and
// lines it rests on. How it is worked out is covered by ScoringApiIntegrationTest and ReachabilityTest.
const REVISION = "0123456789abcdef0123456789abcdef01234567";
const CHANGE = { id: "signal-r", signalType: "VULNERABILITY" as const, sourceId: "osv", externalId: "GHSA-reach", title: "CVE-2026-7777" };

function factors(reachability: "IMPORTED" | "NOT_IMPORTED" | "UNKNOWN"): ScoreFactor[] {
  const notImported = reachability === "NOT_IMPORTED";
  return [
    { factor: "SEVERITY", input: "CVSS 3.1 base 9", weight: 0.9, headroom: notImported ? 8 : 10, limiting: !notImported, setting: null },
    { factor: "EXPOSURE", input: "INTERNET_FACING", weight: 1, headroom: 0, limiting: false, setting: "aegis.scoring.exposure.internet-facing" },
    { factor: "REACHABILITY", input: reachability, weight: notImported ? 0.8 : 1, headroom: notImported ? 18 : 0,
      limiting: notImported, setting: notImported ? "aegis.scoring.reachability.not-imported" : "aegis.scoring.reachability.imported" },
    { factor: "CRITICALITY", input: "CRITICAL", weight: 1, headroom: 0, limiting: false, setting: "aegis.scoring.criticality.critical" },
    { factor: "CONFIDENCE", input: "CONFIRMED", weight: 1, headroom: 0, limiting: false, setting: "aegis.scoring.confidence.confirmed" },
  ];
}

function scored(name: string, reachability: Reachability): ScoredService {
  const status = reachability.status;
  const score = status === "NOT_IMPORTED" ? 72 : 90;
  return {
    service: { id: `service-${name}`, name, criticality: "CRITICAL", exposure: "INTERNET_FACING" },
    score, tier: score >= 90 ? "CRITICAL" : "HIGH", material: true, confidence: "CONFIRMED",
    breakdown: { severityFactor: 0.9, exposure: "INTERNET_FACING", exposureFactor: 1, exposureApplies: true,
      reachability: status, reachabilityFactor: status === "NOT_IMPORTED" ? 0.8 : 1, reachabilityApplies: true,
      criticality: "CRITICAL", criticalityFactor: 1, confidence: "CONFIRMED", confidenceFactor: 1 },
    factors: factors(status), reachability, explanation: `CVE-2026-7777 scores ${score.toFixed(1)} for ${name}`,
    owners: [], evidence: [],
  };
}

function lines(repository: string, ...sites: [string, number][]) {
  return { status: "IMPORTED" as const, reason: null, importedAs: ["x"], siteCount: sites.length,
    sites: sites.map(([path, line]) => ({ path, line, url: `https://github.com/acme/${repository}/blob/${REVISION}/${path}#L${line}` })) };
}

const IMPACT: ChangeImpactResponse = {
  change: CHANGE, matchedComponentCount: 1, matchedVendorCount: 0,
  severity: { source: "CVSS", cvssVersion: "3.1", cvssBaseScore: 9, label: null, factor: 0.9 },
  services: [
    scored("Storefront", { status: "IMPORTED", reason: null, through: null,
      usage: lines("storefront", ["src/app.ts", 4], ["src/b.ts", 9]) }),
    scored("Dashboard", { status: "UNKNOWN", through: "next",
      reason: "postcss isn't imported itself, but it comes in through next, which the code imports",
      usage: lines("dashboard", ["app/layout.tsx", 3]) }),
    scored("Docs site", { status: "NOT_IMPORTED", through: null,
      reason: "Nothing in the code Aegis read imports postcss, nor vite, which pulls it in",
      usage: { status: "NOT_IMPORTED", reason: "Nothing in the code Aegis read imports postcss", importedAs: ["postcss"], siteCount: 0, sites: [] } }),
  ],
  owners: [],
};

async function fakeBff(page: Page) {
  await page.context().addCookies([{ name: "um_refresh", value: "browser-fixture", url: "http://localhost:3100" }]);
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === "/api/auth/me") return route.fulfill({ json: {
      id: "me", email: "me@example.test", displayName: "Me", roles: ["ANALYST"], orgId: "demo-org", enabled: true,
      emailVerified: true, createdAt: "2026-10-01T00:00:00Z",
    } });
    if (url.pathname === "/api/orgs") return route.fulfill({ json: [{ id: "demo-org", name: "Demo org", roles: ["ANALYST"], status: "ACTIVE" }] });
    if (url.pathname === "/api/scoring/change-signals/signal-r") return route.fulfill({ json: IMPACT });
    if (url.pathname === "/api/change-signals/signal-r") return route.fulfill({ json: {
      ...CHANGE, summary: "A flaw in postcss.", url: null, occurredAt: "2026-10-13T00:00:00Z", createdAt: null, updatedAt: null,
      severity: "CRITICAL",
    } });
    return route.fulfill({ json: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } });
  });
}

/** The service's card on the change-impact page. */
function card(page: Page, service: string) {
  return page.locator("div.rounded-lg.border").filter({ has: page.getByRole("link", { name: service, exact: true }) });
}

test("each service's score names reachability with the lines it rests on", async ({ page }) => {
  await fakeBff(page);
  await page.goto("/advisories/signal-r");

  // Imported: the full score, and the lines that import it, linked at the commit read.
  const storefront = card(page, "Storefront");
  await expect(storefront.getByText("Reachability", { exact: true })).toBeVisible();
  await expect(storefront.getByText("Imported by the code")).toBeVisible();
  await storefront.getByText("Imported in 2 places").click();
  await expect(storefront.getByRole("link", { name: "src/app.ts:4" }))
    .toHaveAttribute("href", `https://github.com/acme/storefront/blob/${REVISION}/src/app.ts#L4`);

  // Reached through what pulls it in: unknown, scored like imported, with the lines importing next.
  const dashboard = card(page, "Dashboard");
  await expect(dashboard.getByText("Unknown — scored as imported")).toBeVisible();
  await expect(dashboard.getByText("postcss isn't imported itself, but it comes in through next, which the code imports.")).toBeVisible();
  await dashboard.getByText("next imported in 1 place").click();
  await expect(dashboard.getByRole("link", { name: "app/layout.tsx:3" })).toBeVisible();

  // Never imported, nor what pulls it in: a band lower, and reachability is what holds it down.
  const docs = card(page, "Docs site");
  await expect(docs.getByText("72.0", { exact: true })).toBeVisible();
  await expect(docs.getByText("Not imported, nor what pulls it in")).toBeVisible();
  await expect(docs.getByText("×0.80")).toBeVisible();
  await expect(docs.getByText("−18.0 pts")).toBeVisible();
  await expect(docs.getByText("Nothing in the code Aegis read imports postcss, nor vite, which pulls it in.")).toBeVisible();
  await expect(docs.getByText("Held down most by")).toContainText("reachability");
  await page.screenshot({ path: test.info().outputPath("reachability.png"), fullPage: true });
});

test("the item detail shows the same reachability evidence", async ({ page }) => {
  await fakeBff(page);
  await page.goto("/findings/signal-r");

  await expect(card(page, "Docs site").getByText("Not imported, nor what pulls it in")).toBeVisible();
  await card(page, "Storefront").getByText("Imported in 2 places").click();
  await expect(card(page, "Storefront").getByRole("link", { name: "src/b.ts:9" })).toBeVisible();
});
