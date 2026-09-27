import { expect, test, type Page } from "@playwright/test";
import type { GitHubConnectResult, GitHubIntegration, Role } from "../src/lib/types";

// Browser contract checks use a fake BFF. Who may connect what — and the GitHub sign-in itself — are covered
// by aegis-core's GitHubConnectionsTest and GitHubIntegrationControllerIntegrationTest.
const CONNECTED: GitHubIntegration = {
  enabled: true,
  installUrl: "https://github.com/apps/aegis-cs203/installations/new",
  connections: [{
    installationId: 42, accountLogin: "octo", accountId: 1001, accountType: "User",
    connectedBy: "me", connectedAt: "2026-09-26T01:00:00Z",
  }],
};

async function fakeBff(page: Page, role: Role, integration: GitHubIntegration = CONNECTED,
  finish: { status: number; json: GitHubConnectResult | { message: string } } = {
    status: 200, json: { connected: CONNECTED.connections, skipped: [{ accountLogin: "guild", reason: "Only an admin of the guild GitHub organization can connect it" }] },
  }) {
  const calls: { method: string; path: string; body: unknown }[] = [];
  let current = integration;
  await page.context().addCookies([{ name: "um_refresh", value: "browser-fixture", url: "http://localhost:3000" }]);
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === "/api/auth/me") {
      return route.fulfill({ json: {
        id: "me", email: "me@example.test", displayName: "Me", roles: [role], orgId: "demo-org",
        enabled: true, emailVerified: true, createdAt: "2026-09-01T00:00:00Z",
      } });
    }
    if (path === "/api/orgs") {
      return route.fulfill({ json: [{ id: "demo-org", name: "Demo org", roles: [role], status: "ACTIVE" }] });
    }
    if (path.startsWith("/api/integrations/")) {
      calls.push({ method: request.method(), path, body: request.postDataJSON() });
      if (path === "/api/integrations/github" && request.method() === "GET") {
        return route.fulfill({ json: current });
      }
      if (path === "/api/integrations/github/authorizations") {
        return route.fulfill({ json: { authorizeUrl: "/fake-github-authorize?state=abc" } });
      }
      if (path === "/api/integrations/github/connections") {
        return route.fulfill({ status: finish.status, json: finish.json });
      }
      if (path === "/api/integrations/github/connections/42" && request.method() === "DELETE") {
        current = { ...current, connections: [] };
        return route.fulfill({ status: 204, body: "" });
      }
    }
    return route.fulfill({ json: { content: [], totalElements: 0, totalPages: 1, number: 0, size: 20 } });
  });
  return { calls };
}

test("an organization admin sees what is connected, and connects by signing in to GitHub", async ({ page }) => {
  const { calls } = await fakeBff(page, "ORG_ADMIN");
  await page.goto("/admin/integrations");
  await expect(page.getByRole("heading", { name: "Integrations", level: 1 })).toBeVisible();

  const github = page.getByRole("region", { name: "GitHub" });
  const connected = github.getByRole("list", { name: "Connected GitHub accounts" });
  await expect(connected).toContainText("octo");
  await expect(connected).toContainText("Personal account");
  await expect(connected).toContainText("Connected by you");
  await expect(github.getByRole("link", { name: "Install on GitHub" }))
    .toHaveAttribute("href", "https://github.com/apps/aegis-cs203/installations/new");
  await expect(page.getByRole("link", { name: "Integrations" })).toBeVisible();

  await github.getByRole("button", { name: "Connect GitHub" }).click();
  await page.waitForURL(/\/fake-github-authorize\?state=abc$/);
  expect(calls.filter((call) => call.method === "POST").map((call) => call.path))
    .toEqual(["/api/integrations/github/authorizations"]);
});

test("disconnecting asks first, then the account is gone", async ({ page }) => {
  const { calls } = await fakeBff(page, "ORG_ADMIN");
  await page.goto("/admin/integrations");
  const github = page.getByRole("region", { name: "GitHub" });
  await github.getByRole("button", { name: "Disconnect octo" }).click();
  await page.getByRole("button", { name: "Disconnect", exact: true }).click();

  await expect(github.getByText("No GitHub account is connected yet.")).toBeVisible();
  expect(calls.some((call) => call.method === "DELETE" && call.path === "/api/integrations/github/connections/42")).toBe(true);
});

test("everyone else sees the connection but can't change it", async ({ page }) => {
  await fakeBff(page, "ANALYST");
  await page.goto("/admin/integrations");
  const github = page.getByRole("region", { name: "GitHub" });
  await expect(github.getByRole("list", { name: "Connected GitHub accounts" })).toContainText("octo");
  await expect(github.getByText("Your organization admin connects GitHub.")).toBeVisible();
  await expect(github.getByRole("button")).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Integrations" })).toHaveCount(0);
});

test("a deployment without the GitHub App says so", async ({ page }) => {
  await fakeBff(page, "ORG_ADMIN", { enabled: false, installUrl: null, connections: [] });
  await page.goto("/admin/integrations");
  await expect(page.getByText("GitHub isn't enabled on this Aegis deployment", { exact: false })).toBeVisible();
  await expect(page.getByRole("button", { name: "Connect GitHub" })).toHaveCount(0);
});

test("coming back from GitHub connects once, says what was and wasn't connected, and drops the code from the address", async ({ page }) => {
  const { calls } = await fakeBff(page, "ORG_ADMIN");
  await page.goto("/admin/integrations/github/callback?code=the-code&state=the-state");

  await expect(page.getByRole("list", { name: "Connected", exact: true })).toContainText("octo");
  await expect(page.getByRole("list", { name: "Not connected" }))
    .toContainText("guild — Only an admin of the guild GitHub organization can connect it");
  await expect(page).toHaveURL(/\/admin\/integrations\/github\/callback$/);
  const finishes = calls.filter((call) => call.path === "/api/integrations/github/connections");
  expect(finishes).toEqual([{ method: "POST", path: "/api/integrations/github/connections", body: { code: "the-code", state: "the-state" } }]);
});

test("a refused sign-in says why, and a cancelled one connects nothing", async ({ page }) => {
  const { calls } = await fakeBff(page, "ORG_ADMIN", CONNECTED, {
    status: 403, json: { message: "This GitHub sign-in wasn't started by you in this organization, or it expired; connect again" },
  });
  await page.goto("/admin/integrations/github/callback?code=the-code&state=stale");
  await expect(page.getByRole("alert").filter({ hasText: "GitHub sign-in" })).toHaveText(
    "This GitHub sign-in wasn't started by you in this organization, or it expired; connect again");

  await page.goto("/admin/integrations/github/callback?error=access_denied");
  await expect(page.getByText("You cancelled the GitHub sign-in; nothing was connected.")).toBeVisible();
  expect(calls.filter((call) => call.path === "/api/integrations/github/connections")).toHaveLength(1);
});
