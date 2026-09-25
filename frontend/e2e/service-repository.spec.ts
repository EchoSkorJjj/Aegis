import { expect, test, type Page } from "@playwright/test";
import type { BusinessService, Role } from "../src/lib/types";

// Browser contract checks use a fake BFF. Backend RBAC/tenancy is covered by Java integration tests.
async function fixture(page: Page, role: Role, assigned = false, linked = false) {
  let service: BusinessService = {
    id: "repository-service", name: "Repository demo", criticality: "HIGH", exposure: "INTERNAL",
    repositoryUrl: linked ? "https://github.com/acme/payments" : null,
    repositoryBranch: linked ? "develop" : null,
    createdAt: "2026-09-24T00:00:00Z", updatedAt: "2026-09-24T00:00:00Z",
  };
  const writes: { method: string; body: unknown }[] = [];
  await page.context().addCookies([{ name: "um_refresh", value: "browser-fixture", url: "http://localhost:3000" }]);
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === "/api/auth/me") {
      return route.fulfill({ json: {
        id: "demo-owner", email: "owner@example.test", displayName: "Demo owner",
        roles: [role], orgId: "demo-org", enabled: true, emailVerified: true, createdAt: service.createdAt,
      } });
    }
    if (path === "/api/orgs") {
      return route.fulfill({ json: [{ id: "demo-org", name: "Demo org", roles: [role], status: "ACTIVE" }] });
    }
    if (path.endsWith("/repository")) {
      writes.push({ method: request.method(), body: request.postDataJSON() });
      if (request.method() === "DELETE") {
        service = { ...service, repositoryUrl: null, repositoryBranch: null };
        return route.fulfill({ status: 204 });
      }
      service = {
        ...service, repositoryUrl: "https://github.com/acme/payments",
        repositoryBranch: request.postDataJSON().branch,
      };
      return route.fulfill({ json: service });
    }
    if (path === "/api/inventory/services/repository-service") {
      return route.fulfill({ json: service });
    }
    const content = path.endsWith("/owners") && assigned
      ? [{ id: "owner-row", userId: "demo-owner", name: "Demo owner", email: "owner@example.test" }] : [];
    return route.fulfill({ json: { content, totalElements: content.length, totalPages: 1, number: 0, size: 20 } });
  });
  await page.goto("/inventory/services/repository-service");
  await expect(page.getByRole("heading", { name: "Repository demo" })).toBeVisible();
  return writes;
}

test("governance links, edits, reloads and unlinks a repository; import uses the saved target", async ({ page }) => {
  const writes = await fixture(page, "ANALYST");
  const section = page.getByRole("region", { name: "Service repository" });
  await section.getByRole("button", { name: "Link repository" }).click();
  await page.getByLabel("Repository URL", { exact: true }).fill("https://GitHub.COM/acme/payments.git/");
  await page.getByLabel("Branch (optional)", { exact: true }).fill("develop");
  await page.getByRole("button", { name: "Save repository" }).click();
  const link = section.getByRole("link", { name: "https://github.com/acme/payments" });
  await expect(link).toHaveAttribute("target", "_blank");
  await expect(link).toHaveAttribute("rel", "noopener noreferrer");
  expect(writes[0]).toEqual({ method: "PUT", body: { url: "https://GitHub.COM/acme/payments.git/", branch: "develop" } });
  await page.reload();
  await expect(section.getByText("develop", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Import from GitHub" }).click();
  await expect(page.getByPlaceholder("https://github.com/owner/repo")).toHaveValue("https://github.com/acme/payments");
  await expect(page.getByPlaceholder("main", { exact: true })).toHaveValue("develop");
  await page.keyboard.press("Escape");
  await section.getByRole("button", { name: "Edit repository" }).click();
  await expect(page.getByLabel("Branch (optional)")).toHaveValue("develop");
  await page.getByLabel("Branch (optional)").fill("");
  await page.getByRole("button", { name: "Save repository" }).click();
  await expect(section.getByText("Repository default branch")).toBeVisible();
  expect(writes[1]).toEqual({ method: "PUT", body: { url: "https://github.com/acme/payments", branch: null } });
  await section.getByRole("button", { name: "Unlink repository" }).click();
  await expect(section.getByText("No repository linked.")).toBeVisible();
  expect(writes[2].method).toBe("DELETE");
});

test("an assigned service owner can maintain the link", async ({ page }) => {
  await fixture(page, "SERVICE_OWNER", true, true);
  const section = page.getByRole("region", { name: "Service repository" });
  await expect(section.getByRole("button", { name: "Edit repository" })).toBeVisible();
  await expect(section.getByRole("button", { name: "Unlink repository" })).toBeVisible();
});

test("an unassigned service owner sees the repository without write controls", async ({ page }) => {
  await fixture(page, "SERVICE_OWNER", false, true);
  const section = page.getByRole("region", { name: "Service repository" });
  await expect(section.getByRole("link")).toBeVisible();
  await expect(section.getByRole("button")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Import from GitHub" })).toHaveCount(0);
});

test("a failed save keeps the current link and the entered form values", async ({ page }) => {
  await fixture(page, "COMPLIANCE_ADMIN", false, true);
  await page.route("**/api/inventory/services/repository-service/repository", (route) => route.fulfill({
    status: 400, json: { message: "This host is not allowed" },
  }));
  const section = page.getByRole("region", { name: "Service repository" });
  await section.getByRole("button", { name: "Edit repository" }).click();
  await page.getByLabel("Repository URL", { exact: true }).fill("https://not-allowed.example/acme/repo");
  await page.getByRole("button", { name: "Save repository" }).click();
  await expect(page.getByText("This host is not allowed")).toBeVisible();
  await expect(section.getByRole("link")).toHaveAttribute("href", "https://github.com/acme/payments");
  await expect(page.getByLabel("Repository URL", { exact: true })).toHaveValue("https://not-allowed.example/acme/repo");
});
