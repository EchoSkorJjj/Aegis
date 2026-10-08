import { expect, test, type Page } from "@playwright/test";
import type { BusinessService, Role, UpgradeRecommendation } from "../src/lib/types";

// Browser contract checks use a fake BFF. How recommendations are computed (matching, targets, scoring) is
// covered by UpgradeRecommendationIntegrationTest.
const PORTAL = "service-portal";
const EDU = "service-edu";

function recommendation(overrides: Partial<UpgradeRecommendation>): UpgradeRecommendation {
  return {
    serviceId: PORTAL, serviceName: "Customer Portal",
    component: { id: "axios-0211", ecosystem: "npm", name: "axios", version: "0.21.1" },
    via: null, manifestPaths: ["client/package.json", "server/package.json"], confidence: "CONFIRMED",
    vulnerabilities: [
      { id: "CVE-2026-40175", changeSignalId: "signal-axios", confidence: "CONFIRMED", cvssVersion: "3.1", cvssBaseScore: 7.5, severity: "HIGH" },
      { id: "CVE-2023-45857", changeSignalId: "signal-other", confidence: "CONFIRMED", cvssVersion: "3.1", cvssBaseScore: 6.5, severity: "MEDIUM" },
    ],
    score: 67.5, tier: "HIGH", material: true, toVersion: "1.15.0", changeSignalId: "signal-axios",
    readiness: "READY", reason: null, proposal: null, response: null, canDraft: true,
    ...overrides,
  };
}

const RECOMMENDATIONS: UpgradeRecommendation[] = [
  recommendation({}),
  recommendation({
    serviceId: EDU, serviceName: "EduHelper", component: { id: "log4j", ecosystem: "maven", name: "org.apache.logging.log4j:log4j-core", version: "2.14.1" },
    manifestPaths: ["service/pom.xml"], toVersion: "2.17.1", score: 42.1, tier: "MEDIUM",
    proposal: { id: "proposal-open", status: "OPENED", toVersion: "2.17.1", pullRequestUrl: "https://github.com/acme/edu/pull/9", riskTicketId: "ticket-log4j" },
    response: { ticketId: "ticket-log4j", changeSignalId: "signal-axios", status: "FILED", stage: "ACTIONED", issueKey: "SEC-3", issueUrl: "https://acme.atlassian.net/browse/SEC-3" },
  }),
  recommendation({
    serviceId: EDU, serviceName: "EduHelper", component: { id: "lodash", ecosystem: "npm", name: "lodash", version: "4.17.20" },
    toVersion: "4.17.21", score: 30.2, tier: "MEDIUM", canDraft: false,
  }),
  recommendation({
    serviceId: PORTAL, component: { id: "minimist", ecosystem: "npm", name: "minimist", version: "1.2.0" },
    toVersion: "1.2.6", score: 12.4, tier: "LOW", readiness: "NO_REPOSITORY",
    reason: "Link the service's repository so Aegis can draft the pull request",
  }),
  recommendation({
    serviceId: EDU, serviceName: "EduHelper", component: { id: "follow-redirects", ecosystem: "npm", name: "follow-redirects", version: "1.15.4" },
    via: { id: "axios-165", ecosystem: "npm", name: "axios", version: "1.6.5" }, toVersion: "1.15.6", score: 9.0, tier: "LOW",
    readiness: "TRANSITIVE", reason: "follow-redirects comes in through axios; upgrade axios, or pin follow-redirects to 1.15.6 by hand",
  }),
];

async function fakeBff(page: Page, role: Role, recommendations: UpgradeRecommendation[] = RECOMMENDATIONS) {
  const drafts: unknown[] = [];
  const listed: (string | null)[] = [];
  const service: BusinessService = {
    id: EDU, name: "EduHelper", criticality: "HIGH", exposure: "INTERNET_FACING",
    repositoryUrl: "https://github.com/acme/edu", repositoryBranch: null,
    sbomImportedAt: "2026-09-26T01:00:00Z", sbomImportSource: "PUBLIC",
    createdAt: "2026-09-24T00:00:00Z", updatedAt: "2026-09-24T00:00:00Z",
  };
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
    if (path === "/api/change-signals/facets") {
      return route.fulfill({ json: { sources: [], severities: [], ecosystems: [] } });
    }
    if (path === "/api/actions/upgrade-recommendations") {
      const serviceId = url.searchParams.get("serviceId");
      listed.push(serviceId);
      return route.fulfill({ json: serviceId ? recommendations.filter((item) => item.serviceId === serviceId) : recommendations });
    }
    if (path === "/api/actions/risk-tickets" && request.method() === "POST") {
      drafts.push(request.postDataJSON());
      return route.fulfill({ status: 201, json: { id: "ticket-new", pullRequests: [] } });
    }
    if (path.startsWith("/api/actions/risk-tickets/")) {
      return route.fulfill({ status: 404, json: { message: "Risk ticket not found" } });
    }
    if (path === `/api/inventory/services/${EDU}`) {
      return route.fulfill({ json: service });
    }
    return route.fulfill({ json: { content: [], totalElements: 0, totalPages: 1, number: 0, size: 20 } });
  });
  return { drafts, listed };
}

test("a service offers responses for the upgrades Aegis found — no CVE to look up", async ({ page }) => {
  const { drafts, listed } = await fakeBff(page, "ANALYST", RECOMMENDATIONS
    .filter((item) => item.readiness !== "TRANSITIVE").map((item) => ({ ...item, serviceId: EDU })));
  await page.goto(`/inventory/services/${EDU}`);

  const recommended = page.getByRole("region", { name: "Vulnerable dependencies" });
  await expect(recommended.getByRole("row")).toHaveCount(1 + 4);
  await expect(recommended.getByText("follow-redirects")).toHaveCount(0);

  const axios = recommended.getByRole("row").filter({ hasText: "axios" });
  await expect(axios).toContainText("0.21.1 → 1.15.0");
  await expect(axios).toContainText("Fixes CVE-2026-40175 and 1 more");
  await expect(axios.getByRole("link", { name: "CVE-2026-40175" })).toHaveAttribute("href", "/advisories/signal-axios");

  await expect(axios).toContainText("client/package.json, server/package.json");

  // A response already drafted is linked, not offered again.
  const log4j = recommended.getByRole("row").filter({ hasText: "log4j-core" });
  await expect(log4j.getByText("Filed", { exact: true })).toBeVisible();
  await expect(log4j.getByRole("link", { name: "View response →" })).toHaveAttribute("href", "/responses/tickets/ticket-log4j");
  await expect(log4j.getByRole("button")).toHaveCount(0);

  // Nothing to automate until the repository is linked: the response is the ticket alone.
  const minimist = recommended.getByRole("row").filter({ hasText: "minimist" });
  await expect(minimist).toContainText("Ticket only until the service's repository is linked.");
  await expect(minimist.getByRole("link", { name: "Link it →" })).toHaveAttribute("href", `/inventory/services/${EDU}`);
  await expect(minimist.getByRole("button", { name: "Draft response" })).toBeVisible();

  const lodash = recommended.getByRole("row").filter({ hasText: "lodash" });
  await expect(lodash.getByRole("button")).toHaveCount(0);
  await expect(lodash).toContainText("An analyst or the service's owner can draft a response");

  await axios.getByRole("button", { name: "Draft response" }).click();
  await expect(page).toHaveURL(/\/responses\/tickets\/ticket-new$/);
  expect(drafts).toEqual([{ changeSignalId: "signal-axios", serviceId: EDU }]);
  expect([...new Set(listed)]).toEqual([EDU]);
});

test("a service's page lists its vulnerable dependencies, including those another package pulls in", async ({ page }) => {
  const { listed } = await fakeBff(page, "SERVICE_OWNER");
  await page.goto(`/inventory/services/${EDU}`);
  await expect(page.getByText("Inventory / Services")).toBeVisible();

  const vulnerable = page.getByRole("region", { name: "Vulnerable dependencies" });
  await expect(vulnerable.getByRole("row")).toHaveCount(1 + 3);
  await expect(vulnerable.getByRole("columnheader", { name: "Service" })).toHaveCount(0);
  const transitive = vulnerable.getByRole("row").filter({ hasText: "follow-redirects" });
  await expect(transitive).toContainText(
    "Ticket only: follow-redirects comes in through axios; upgrade axios, or pin follow-redirects to 1.15.6 by hand");
  // Nothing to upgrade automatically still gets a response: its ticket alone.
  await expect(transitive.getByRole("button", { name: "Draft response" })).toBeVisible();
  expect([...new Set(listed)]).toEqual([EDU]);
});

test("the header names where you are, as the sidebar does", async ({ page }) => {
  await fakeBff(page, "ANALYST");
  await page.goto("/advisories");
  await expect(page.getByText("Advisories", { exact: true }).first()).toBeVisible();
  await page.goto("/responses/history");
  await expect(page.getByText("Responses / History")).toBeVisible();
  await expect(page.getByRole("region", { name: "Recommended upgrades" })).toHaveCount(0);
});

test("a service's upgrades filter by package, impact and ecosystem", async ({ page }) => {
  await fakeBff(page, "ANALYST", RECOMMENDATIONS.filter((item) => item.readiness !== "TRANSITIVE").map((item) => ({ ...item, serviceId: EDU })));
  await page.goto(`/inventory/services/${EDU}`);
  const recommended = page.getByRole("region", { name: "Vulnerable dependencies" });
  const packages = () => recommended.getByRole("row").filter({ has: page.getByRole("cell") });
  await expect(packages()).toHaveCount(4);

  await recommended.getByLabel("Impact").selectOption("MEDIUM");
  await expect(packages()).toHaveCount(2);
  await recommended.getByLabel("Ecosystem").selectOption("npm");
  await expect(packages()).toHaveCount(1);
  await expect(packages().first()).toContainText("lodash");
  await recommended.getByLabel("Package").fill("axi");
  await expect(recommended.getByText("Nothing matches these filters.")).toBeVisible();
  await recommended.getByRole("button", { name: "Clear" }).click();
  await expect(packages()).toHaveCount(4);

  await expect(recommended.getByRole("button", { name: "Filter by service" })).toHaveCount(0);
});

test("a service's vulnerable dependencies are paged, and filters return to the first page", async ({ page }) => {
  const many = Array.from({ length: 12 }, (_, index) => recommendation({
    serviceId: EDU, serviceName: "EduHelper",
    component: { id: `lib-${index}`, ecosystem: index % 2 === 0 ? "npm" : "maven", name: `lib-${String(index).padStart(2, "0")}`, version: "1.0.0" },
    score: 90 - index, tier: index < 6 ? "HIGH" : "LOW",
  }));
  await fakeBff(page, "ANALYST", many);
  await page.goto(`/inventory/services/${EDU}`);
  const vulnerable = page.getByRole("region", { name: "Vulnerable dependencies" });
  const packages = () => vulnerable.getByRole("row").filter({ has: page.getByRole("cell") });
  await expect(vulnerable.getByRole("heading")).toContainText("(12)");
  await expect(vulnerable.getByRole("button", { name: "Filter by service" })).toHaveCount(0);
  await expect(packages()).toHaveCount(10);
  await expect(packages().first()).toContainText("lib-00");

  await page.getByRole("button", { name: "Page 2", exact: true }).click();
  await expect(packages()).toHaveCount(2);
  await expect(packages().first()).toContainText("lib-10");

  await vulnerable.getByLabel("Impact").selectOption("LOW");
  await expect(packages()).toHaveCount(6);
  await expect(packages().first()).toContainText("lib-06");
  await vulnerable.getByLabel("Ecosystem").selectOption("maven");
  await expect(packages()).toHaveCount(3);
});

