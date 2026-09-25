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
    via: null, manifestPath: "client/package.json", confidence: "CONFIRMED",
    vulnerabilities: [
      { id: "CVE-2026-40175", changeSignalId: "signal-axios", confidence: "CONFIRMED", cvssVersion: "3.1", cvssBaseScore: 7.5, severity: "HIGH" },
      { id: "CVE-2023-45857", changeSignalId: "signal-other", confidence: "CONFIRMED", cvssVersion: "3.1", cvssBaseScore: 6.5, severity: "MEDIUM" },
    ],
    score: 67.5, tier: "HIGH", material: true, toVersion: "1.15.0", changeSignalId: "signal-axios",
    readiness: "READY", reason: null, proposal: null, canDraft: true,
    ...overrides,
  };
}

const RECOMMENDATIONS: UpgradeRecommendation[] = [
  recommendation({}),
  recommendation({
    serviceId: EDU, serviceName: "EduHelper", component: { id: "log4j", ecosystem: "maven", name: "org.apache.logging.log4j:log4j-core", version: "2.14.1" },
    manifestPath: "service/pom.xml", toVersion: "2.17.1", score: 42.1, tier: "MEDIUM",
    proposal: { id: "proposal-open", status: "OPENED", toVersion: "2.17.1", pullRequestUrl: "https://github.com/acme/edu/pull/9" },
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
    if (path === "/api/actions/upgrade-recommendations") {
      const serviceId = url.searchParams.get("serviceId");
      listed.push(serviceId);
      return route.fulfill({ json: serviceId ? recommendations.filter((item) => item.serviceId === serviceId) : recommendations });
    }
    if (path === "/api/actions/upgrade-proposals" && request.method() === "POST") {
      drafts.push(request.postDataJSON());
      return route.fulfill({ status: 201, json: { id: "proposal-new" } });
    }
    if (path.startsWith("/api/actions/upgrade-proposals/")) {
      return route.fulfill({ status: 404, json: { message: "Upgrade proposal not found" } });
    }
    if (path === `/api/inventory/services/${EDU}`) {
      return route.fulfill({ json: service });
    }
    return route.fulfill({ json: { content: [], totalElements: 0, totalPages: 1, number: 0, size: 20 } });
  });
  return { drafts, listed };
}

test("the review queue leads with the upgrades Aegis found — no CVE to look up", async ({ page }) => {
  const { drafts, listed } = await fakeBff(page, "ANALYST");
  await page.goto("/responses/review");
  await expect(page.getByText("Responses / Review & approve")).toBeVisible();

  const recommended = page.getByRole("region", { name: "Recommended upgrades" });
  // What another package pulls in is left to each service's page.
  await expect(recommended.getByRole("row")).toHaveCount(1 + 4);
  await expect(recommended.getByText("follow-redirects")).toHaveCount(0);
  await expect(page.getByText("1 more vulnerable dependency comes in through other packages", { exact: false })).toBeVisible();

  const axios = recommended.getByRole("row").filter({ hasText: "axios" });
  await expect(axios).toContainText("0.21.1 → 1.15.0");
  await expect(axios).toContainText("Fixes CVE-2026-40175 and 1 more");
  await expect(axios.getByRole("link", { name: "CVE-2026-40175" })).toHaveAttribute("href", "/advisories/signal-axios");
  await expect(axios.getByRole("link", { name: "Customer Portal" })).toHaveAttribute("href", `/inventory/services/${PORTAL}`);

  const log4j = recommended.getByRole("row").filter({ hasText: "log4j-core" });
  await expect(log4j.getByText("Draft PR open")).toBeVisible();
  await expect(log4j.getByRole("link", { name: "View proposal →" })).toHaveAttribute("href", "/responses/upgrades/proposal-open");

  const minimist = recommended.getByRole("row").filter({ hasText: "minimist" });
  await expect(minimist.getByRole("link", { name: "Link the repository →" })).toHaveAttribute("href", `/inventory/services/${PORTAL}`);

  const lodash = recommended.getByRole("row").filter({ hasText: "lodash" });
  await expect(lodash.getByRole("button")).toHaveCount(0);
  await expect(lodash).toContainText("An analyst or the service's owner can draft it");

  await axios.getByRole("button", { name: "Draft PR" }).click();
  await expect(page).toHaveURL(/\/responses\/upgrades\/proposal-new$/);
  expect(drafts).toEqual([{ changeSignalId: "signal-axios", serviceId: PORTAL, componentId: "axios-0211" }]);
  expect([...new Set(listed)]).toEqual([null]);
});

test("a service's page lists its vulnerable dependencies, including those another package pulls in", async ({ page }) => {
  const { listed } = await fakeBff(page, "SERVICE_OWNER");
  await page.goto(`/inventory/services/${EDU}`);
  await expect(page.getByText("Inventory / Services")).toBeVisible();

  const vulnerable = page.getByRole("region", { name: "Vulnerable dependencies" });
  await expect(vulnerable.getByRole("row")).toHaveCount(1 + 3);
  await expect(vulnerable.getByRole("columnheader", { name: "Service" })).toHaveCount(0);
  const transitive = vulnerable.getByRole("row").filter({ hasText: "follow-redirects" });
  await expect(transitive).toContainText("follow-redirects comes in through axios; upgrade axios, or pin follow-redirects to 1.15.6 by hand");
  await expect(transitive.getByRole("button")).toHaveCount(0);
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

test("recommended upgrades filter by package, impact, ecosystem and several services at once", async ({ page }) => {
  await fakeBff(page, "ANALYST");
  await page.goto("/responses/review");
  const recommended = page.getByRole("region", { name: "Recommended upgrades" });
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

  // Services is the one multi-select: pick two, then drop one.
  await recommended.getByRole("button", { name: "Filter by service" }).click();
  await page.getByRole("menuitemcheckbox", { name: "Customer Portal" }).click();
  await expect(packages()).toHaveCount(2);
  await page.getByRole("menuitemcheckbox", { name: "EduHelper" }).click();
  await expect(packages()).toHaveCount(4);
  await page.getByRole("menuitemcheckbox", { name: "Customer Portal" }).click();
  await page.keyboard.press("Escape");
  await expect(recommended.getByRole("button", { name: "Filter by service" })).toHaveText("EduHelper");
  await expect(packages()).toHaveCount(2);
  await expect(recommended).not.toContainText("axios 0.21.1");
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

