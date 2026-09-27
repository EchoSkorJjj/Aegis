import { expect, test, type Page } from "@playwright/test";
import type { JiraIntegration, JiraOptions, Role } from "../src/lib/types";

// Browser contract checks use a fake BFF. Who may connect, the Atlassian grant itself and the sealed tokens
// are covered by aegis-core's JiraAdapterTest and JiraIntegrationControllerIntegrationTest.
const NOT_CONNECTED: JiraIntegration = {
  enabled: true, connected: false, siteName: null, siteUrl: null, projectKey: null, issueType: null,
  connectedBy: null, connectedAt: null,
};
const CONNECTED: JiraIntegration = {
  enabled: true, connected: true, siteName: "Acme", siteUrl: "https://acme.atlassian.net", projectKey: null,
  issueType: null, connectedBy: "me", connectedAt: "2026-09-26T01:00:00Z",
};
const CONFIGURED: JiraIntegration = { ...CONNECTED, projectKey: "SEC", issueType: "Task" };

function options(cloudId: string | null, projectKey: string | null): JiraOptions {
  return {
    sites: [{ cloudId: "cloud-1", name: "Acme", url: "https://acme.atlassian.net" }],
    cloudId: cloudId ?? "cloud-1",
    projects: [{ key: "OPS", name: "Operations" }, { key: "SEC", name: "Security" }],
    issueTypes: projectKey === "SEC" ? ["Bug", "Task"] : projectKey === "OPS" ? ["Incident"] : [],
  };
}

async function fakeBff(page: Page, role: Role, integration: JiraIntegration,
  finish: { status: number; json: JiraIntegration | { message: string } } = { status: 200, json: CONNECTED }) {
  const calls: { method: string; path: string; body: unknown }[] = [];
  let current = integration;
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
    if (path === "/api/integrations/github") {
      return route.fulfill({ json: { enabled: false, installUrl: null, connections: [] } });
    }
    if (path.startsWith("/api/integrations/jira")) {
      calls.push({ method: request.method(), path, body: request.postDataJSON() });
      if (path === "/api/integrations/jira" && request.method() === "GET") {
        return route.fulfill({ json: current });
      }
      if (path === "/api/integrations/jira/authorizations") {
        return route.fulfill({ json: { authorizeUrl: "/fake-atlassian-authorize?state=abc" } });
      }
      if (path === "/api/integrations/jira/connections") {
        return route.fulfill({ status: finish.status, json: finish.json });
      }
      if (path === "/api/integrations/jira/options") {
        return route.fulfill({ json: options(url.searchParams.get("cloudId"), url.searchParams.get("projectKey")) });
      }
      if (path === "/api/integrations/jira/settings" && request.method() === "PUT") {
        const body = request.postDataJSON();
        current = { ...current, projectKey: body.projectKey, issueType: body.issueType };
        return route.fulfill({ json: current });
      }
      if (path === "/api/integrations/jira/connection" && request.method() === "DELETE") {
        current = NOT_CONNECTED;
        return route.fulfill({ status: 204, body: "" });
      }
    }
    return route.fulfill({ json: { content: [], totalElements: 0, totalPages: 1, number: 0, size: 20 } });
  });
  return { calls };
}

test("an organization admin connects Jira through Atlassian's consent screen", async ({ page }) => {
  const { calls } = await fakeBff(page, "ORG_ADMIN", NOT_CONNECTED);
  await page.goto("/admin/integrations");
  const jira = page.getByRole("region", { name: "Jira" });
  await expect(jira.getByText("Jira isn't connected yet.")).toBeVisible();
  await expect(jira.getByRole("link", { name: "What Aegis keeps" })).toHaveAttribute("href", "/privacy#jira");

  await jira.getByRole("button", { name: "Connect Jira" }).click();
  await page.waitForURL(/\/fake-atlassian-authorize\?state=abc$/);
  expect(calls.filter((call) => call.method === "POST").map((call) => call.path))
    .toEqual(["/api/integrations/jira/authorizations"]);
});

test("once connected, the admin chooses the project and issue type risk tickets are filed as", async ({ page }) => {
  const { calls } = await fakeBff(page, "ORG_ADMIN", CONNECTED);
  await page.goto("/admin/integrations");
  const jira = page.getByRole("region", { name: "Jira" });
  await expect(jira.getByText("No project chosen yet", { exact: false })).toBeVisible();
  await expect(jira.getByRole("link", { name: "Acme" })).toHaveAttribute("href", "https://acme.atlassian.net");

  const form = jira.getByRole("form", { name: "Where risk tickets go" });
  await expect(form.getByRole("button", { name: "Save" })).toBeDisabled();
  await form.getByLabel("Project").selectOption("SEC");
  // Task is Jira's usual type for this, so it is picked when the project has it.
  await expect(form.getByLabel("Issue type")).toHaveValue("Task");
  await form.getByRole("button", { name: "Save" }).click();

  await expect(jira.getByRole("definition").filter({ hasText: "SEC" })).toContainText("SEC as a Task");
  await expect(form).toHaveCount(0);
  expect(calls.filter((call) => call.method === "PUT")).toEqual([{
    method: "PUT", path: "/api/integrations/jira/settings", body: { cloudId: "cloud-1", projectKey: "SEC", issueType: "Task" },
  }]);
});

test("the admin can change the project, or disconnect after confirming", async ({ page }) => {
  const { calls } = await fakeBff(page, "ORG_ADMIN", CONFIGURED);
  await page.goto("/admin/integrations");
  const jira = page.getByRole("region", { name: "Jira" });
  await jira.getByRole("button", { name: "Change project" }).click();
  const form = jira.getByRole("form", { name: "Where risk tickets go" });
  await expect(form.getByLabel("Project")).toHaveValue("SEC");
  await form.getByRole("button", { name: "Cancel" }).click();
  await expect(form).toHaveCount(0);

  await jira.getByRole("button", { name: "Disconnect" }).click();
  await page.getByRole("button", { name: "Disconnect", exact: true }).last().click();
  await expect(jira.getByText("Jira isn't connected yet.")).toBeVisible();
  expect(calls.some((call) => call.method === "DELETE" && call.path === "/api/integrations/jira/connection")).toBe(true);
});

test("everyone else sees where tickets go but can't change it", async ({ page }) => {
  await fakeBff(page, "ANALYST", CONFIGURED);
  await page.goto("/admin/integrations");
  const jira = page.getByRole("region", { name: "Jira" });
  await expect(jira).toContainText("SEC as a Task");
  await expect(jira.getByText("Your organization admin manages the Jira connection.")).toBeVisible();
  await expect(jira.getByRole("button")).toHaveCount(0);
});

test("a deployment without Jira says so", async ({ page }) => {
  await fakeBff(page, "ORG_ADMIN", { ...NOT_CONNECTED, enabled: false });
  await page.goto("/admin/integrations");
  const jira = page.getByRole("region", { name: "Jira" });
  await expect(jira.getByText("Jira isn't enabled on this Aegis deployment", { exact: false })).toBeVisible();
  await expect(jira.getByRole("button")).toHaveCount(0);
});

test("coming back from Atlassian connects once, asks for the project next, and drops the code from the address", async ({ page }) => {
  const { calls } = await fakeBff(page, "ORG_ADMIN", NOT_CONNECTED);
  await page.goto("/admin/integrations/jira/callback?code=the-code&state=the-state");

  await expect(page.getByText("Connected to Acme")).toBeVisible();
  await expect(page.getByRole("link", { name: "Choose the project" })).toHaveAttribute("href", "/admin/integrations");
  await expect(page).toHaveURL(/\/admin\/integrations\/jira\/callback$/);
  expect(calls.filter((call) => call.path === "/api/integrations/jira/connections")).toEqual([
    { method: "POST", path: "/api/integrations/jira/connections", body: { code: "the-code", state: "the-state" } },
  ]);
});

test("a refused connection says why, and a cancelled one connects nothing", async ({ page }) => {
  const refused = "This Jira sign-in wasn't started by you in this organization, or it expired; connect again";
  const { calls } = await fakeBff(page, "ORG_ADMIN", NOT_CONNECTED, { status: 403, json: { message: refused } });
  await page.goto("/admin/integrations/jira/callback?code=the-code&state=stale");
  await expect(page.getByRole("alert").filter({ hasText: "Jira sign-in" })).toHaveText(refused);

  await page.goto("/admin/integrations/jira/callback?error=access_denied");
  await expect(page.getByText("You cancelled on Atlassian's consent screen; nothing was connected.")).toBeVisible();
  expect(calls.filter((call) => call.path === "/api/integrations/jira/connections")).toHaveLength(1);
});
